/*
 * Copyright (C) 2021-2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.reconnect;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.system.NodeId;
import com.swirlds.common.system.address.Address;
import com.swirlds.common.system.address.AddressBook;
import com.swirlds.common.test.fixtures.RandomAddressBookGenerator;
import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.common.test.merkle.util.PairedStreams;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.SocketConnection;
import com.swirlds.platform.state.RandomSignedStateGenerator;
import com.swirlds.platform.state.State;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateValidator;
import com.swirlds.test.framework.TestTypeTags;
import com.swirlds.test.framework.context.TestPlatformContextBuilder;
import java.io.IOException;
import java.security.PublicKey;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Originally this class used {@link java.io.PipedInputStream} and {@link java.io.PipedOutputStream}, but the reconnect
 * methods use two threads to write data, and {@link java.io.PipedOutputStream} keeps a reference to the original thread
 * that started writing data (which is in the reconnect-phase). Then, we send signatures through the current thread
 * (which is different from the first thread that started sending data). At this point,
 * {@link java.io.PipedOutputStream} checks if the first thread is alive, and if not, it will throw an
 * {@link IOException} with the message {@code write end dead}. This is a non-deterministic behavior, but usually
 * running the test 15 times would make the test fail.
 */
final class ReconnectTest {

    private static final Duration RECONNECT_SOCKET_TIMEOUT = Duration.of(1_000, ChronoUnit.MILLIS);

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("com.swirlds.platform.state");
        registry.registerConstructables("com.swirlds.platform.state.signed");
    }

    @Test
    @Tag(TestTypeTags.FUNCTIONAL)
    @DisplayName("Successfully reconnects multiple times and stats are updated")
    void statsTrackSuccessfulReconnect() throws IOException, InterruptedException {
        final int numberOfReconnects = 11;

        final ReconnectMetrics reconnectMetrics = mock(ReconnectMetrics.class);

        for (int index = 1; index <= numberOfReconnects; index++) {
            executeReconnect(reconnectMetrics);
            verify(reconnectMetrics, times(index)).incrementReceiverStartTimes();
            verify(reconnectMetrics, times(index)).incrementSenderStartTimes();
            verify(reconnectMetrics, times(index)).incrementReceiverEndTimes();
            verify(reconnectMetrics, times(index)).incrementSenderEndTimes();
        }
    }

    private void executeReconnect(final ReconnectMetrics reconnectMetrics) throws InterruptedException, IOException {

        final long weightPerNode = 100L;
        final int numNodes = 4;
        final List<NodeId> nodeIds =
                IntStream.range(0, numNodes).mapToObj(NodeId::new).toList();
        final Random random = RandomUtils.getRandomPrintSeed();

        final AddressBook addressBook = new RandomAddressBookGenerator(random)
                .setSize(numNodes)
                .setAverageWeight(weightPerNode)
                .setWeightDistributionStrategy(RandomAddressBookGenerator.WeightDistributionStrategy.BALANCED)
                .setHashStrategy(RandomAddressBookGenerator.HashStrategy.REAL_HASH)
                .build();

        try (final PairedStreams pairedStreams = new PairedStreams()) {
            final SignedState signedState = new RandomSignedStateGenerator()
                    .setAddressBook(addressBook)
                    .setSigningNodeIds(nodeIds)
                    .build();

            final MerkleCryptography cryptography = MerkleCryptoFactory.getInstance();
            cryptography.digestSync(signedState.getState().getPlatformState());
            cryptography.digestSync(signedState.getState());

            final ReconnectLearner receiver = buildReceiver(
                    signedState.getState(),
                    new DummyConnection(pairedStreams.getLearnerInput(), pairedStreams.getLearnerOutput()),
                    reconnectMetrics);

            final Thread thread = new Thread(() -> {
                try {
                    final ReconnectTeacher sender = buildSender(
                            signedState.reserve("test"),
                            new DummyConnection(pairedStreams.getTeacherInput(), pairedStreams.getTeacherOutput()),
                            reconnectMetrics);
                    sender.execute(signedState);
                } catch (final IOException ex) {
                    ex.printStackTrace();
                }
            });

            thread.start();
            receiver.execute(mock(SignedStateValidator.class));
            thread.join();
        }
    }

    private AddressBook buildAddressBook(final int numAddresses) {
        final PublicKey publicKey = mock(PublicKey.class);
        final List<Address> addresses = new ArrayList<>();
        for (int i = 0; i < numAddresses; i++) {
            final Address address = mock(Address.class);
            when(address.getSigPublicKey()).thenReturn(publicKey);
            when(address.getNodeId()).thenReturn(new NodeId(i));
            addresses.add(address);
        }
        return new AddressBook(addresses);
    }

    private ReconnectTeacher buildSender(
            final ReservedSignedState signedState,
            final SocketConnection connection,
            final ReconnectMetrics reconnectMetrics)
            throws IOException {

        final NodeId selfId = new NodeId(0);
        final NodeId otherId = new NodeId(3);
        final long lastRoundReceived = 100;
        return new ReconnectTeacher(
                getStaticThreadManager(),
                connection,
                RECONNECT_SOCKET_TIMEOUT,
                selfId,
                otherId,
                lastRoundReceived,
                () -> false,
                reconnectMetrics);
    }

    private ReconnectLearner buildReceiver(
            final State state, final Connection connection, final ReconnectMetrics reconnectMetrics) {
        final AddressBook addressBook = buildAddressBook(5);

        return new ReconnectLearner(
                TestPlatformContextBuilder.create().build(),
                getStaticThreadManager(),
                connection,
                addressBook,
                state,
                RECONNECT_SOCKET_TIMEOUT,
                reconnectMetrics);
    }
}
