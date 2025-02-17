/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.config.VersionedConfigImpl;
import com.hedera.node.app.fixtures.state.FakeHederaState;
import com.hedera.node.app.fixtures.state.FakeSchemaRegistry;
import com.hedera.node.app.info.NetworkInfoImpl;
import com.hedera.node.app.info.SelfNodeInfoImpl;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.spi.Service;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.spi.fixtures.TestBase;
import com.hedera.node.app.spi.fixtures.TransactionFactory;
import com.hedera.node.app.spi.fixtures.state.MapWritableKVState;
import com.hedera.node.app.spi.fixtures.state.MapWritableStates;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.info.SelfNodeInfo;
import com.hedera.node.app.spi.state.ReadableStates;
import com.hedera.node.app.spi.state.WritableStates;
import com.hedera.node.app.state.HederaState;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.app.version.HederaSoftwareVersion;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.metrics.Counter;
import com.swirlds.common.metrics.Metrics;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultMetrics;
import com.swirlds.common.metrics.platform.DefaultMetricsFactory;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.system.NodeId;
import com.swirlds.common.system.Platform;
import com.swirlds.common.system.SwirldState;
import com.swirlds.common.system.address.Address;
import com.swirlds.common.system.address.AddressBook;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.test.framework.config.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Most of the components in this module have rich and interesting dependencies. While we can (and at times must) mock
 * these dependencies out, especially to test a variety of negative test scenarios, it is often better to test using
 * something more approximating a real environment. Such tests are less brittle to changes in the codebase, and also
 * tend to find issues earlier in the development cycle. They may also result in test failures in seemingly unrelated
 * tests. For example, if all tests use {@link HederaState}, and the implementation of that has a bug, a large number
 * of tests that are only indirectly related to {@link HederaState} will still fail.
 *
 * <p>The real challenge is that many of these dependencies are not easy to set up. In addition, from test to test,
 * you may want *almost* everything setup as normal, but with a small tweak in one place or another.
 *
 * <p>This test base class is designed to make it easy to set up a test environment that is as close to the real thing
 * as _necessary_. For example, it uses the fake Map-based implementations of the {@link WritableStates} and the
 * {@link ReadableStates} interfaces rather than the merkle tree implementation. But it still uses a real-ish startup
 * sequence for loading schemas and so forth.
 *
 * <p>It provides a builder for setting up the test environment. This builder will allow you to specify the config, the
 * services you want to initialize, the initial starting state you want to use, etc. It will build an object with
 * methods for getting implementations of various interfaces that you can use in your tests.
 */
public class AppTestBase extends TestBase implements TransactionFactory, Scenarios {

    // For many of our tests we need to have metrics available, and an easy way to test the metrics
    // are being set appropriately.
    /** Used as a dependency to the {@link Metrics} system. */
    private static final ScheduledExecutorService METRIC_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    private static final String ACCOUNTS_KEY = "ACCOUNTS";
    private static final String ALIASES_KEY = "ALIASES";
    public static final String ALICE_ALIAS = "Alice Alias";
    protected MapWritableKVState<AccountID, Account> accountsState;
    protected MapWritableKVState<String, AccountID> aliasesState;
    protected HederaState state;

    protected void setupStandardStates() {
        accountsState = new MapWritableKVState<>(
                ACCOUNTS_KEY,
                Map.of(
                        ALICE.accountID(), ALICE.account(),
                        ERIN.accountID(), ERIN.account(),
                        STAKING_REWARD_ACCOUNT.accountID(), STAKING_REWARD_ACCOUNT.account()));
        aliasesState = new MapWritableKVState<>(ALIASES_KEY, Map.of());
        final var writableStates = MapWritableStates.builder()
                .state(accountsState)
                .state(aliasesState)
                .build();

        state = new HederaState() {
            @NonNull
            @Override
            public ReadableStates createReadableStates(@NonNull String serviceName) {
                return TokenService.NAME.equals(serviceName) ? writableStates : null;
            }

            @NonNull
            @Override
            public WritableStates createWritableStates(@NonNull String serviceName) {
                return TokenService.NAME.equals(serviceName) ? writableStates : null;
            }
        };
    }

    private final HederaSoftwareVersion softwareVersion = new HederaSoftwareVersion(
            SemanticVersion.newBuilder().major(1).minor(2).patch(3).build(),
            SemanticVersion.newBuilder().major(1).minor(2).patch(3).build());
    /** Represents "this node" in our tests. */
    private final NodeId nodeSelfId = new NodeId(7);
    /** The AccountID of "this node" in our tests. */
    protected final AccountID nodeSelfAccountId =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(8).build();

    protected final SelfNodeInfo selfNodeInfo =
            new SelfNodeInfoImpl(7, nodeSelfAccountId, false, "Node7", softwareVersion);

    /**
     * The gRPC system has extensive metrics. This object allows us to inspect them and make sure they are being set
     * correctly for different types of calls.
     */
    protected final Metrics metrics;

    public AppTestBase() {
        final Configuration configuration = HederaTestConfigBuilder.createConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        this.metrics = new DefaultMetrics(
                nodeSelfId, new MetricKeyRegistry(), METRIC_EXECUTOR, new DefaultMetricsFactory(), metricsConfig);
    }

    protected Counter counterMetric(final String name) {
        return (Counter) metrics.getMetric("app", name);
    }

    protected SpeedometerMetric speedometerMetric(final String name) {
        return (SpeedometerMetric) metrics.getMetric("app", name);
    }

    protected TestAppBuilder appBuilder() {
        return new TestAppBuilder();
    }

    public interface App {
        @NonNull
        HederaSoftwareVersion softwareVersion();

        @NonNull
        WorkingStateAccessor workingStateAccessor();

        @NonNull
        NetworkInfo networkInfo();

        @NonNull
        ConfigProvider configProvider();

        @NonNull
        Platform platform();

        @NonNull
        StateMutator stateMutator(@NonNull final String serviceName);
    }

    public static final class StateMutator {
        private final MapWritableStates writableStates;

        private StateMutator(@NonNull final MapWritableStates states) {
            this.writableStates = states;
        }

        public <T> StateMutator withSingletonState(@NonNull final String stateKey, @NonNull final T value) {
            writableStates.getSingleton(stateKey).put(value);
            return this;
        }

        public <K, V> StateMutator withKVState(
                @NonNull final String stateKey, @NonNull final K key, @NonNull final V value) {
            writableStates.get(stateKey).put(key, value);
            return this;
        }

        public <T> StateMutator withQueueState(@NonNull final String stateKey, @NonNull final T value) {
            writableStates.getQueue(stateKey).add(value);
            return this;
        }

        public void commit() {
            writableStates.commit();
        }
    }

    public static final class TestAppBuilder {
        private SemanticVersion softwareVersion = SemanticVersion.DEFAULT;
        private SemanticVersion hapiVersion = SemanticVersion.DEFAULT;
        private Set<Service> services = new LinkedHashSet<>();
        private TestConfigBuilder configBuilder = HederaTestConfigBuilder.create();
        private NodeInfo selfNodeInfo = null;
        private Set<NodeInfo> nodes = new LinkedHashSet<>();

        private TestAppBuilder() {}

        /**
         * Specify a service to include in this test application configuration. The schemas for this service will be
         * configured.
         *
         * @param service The service to include.
         * @return a reference to this builder
         */
        public TestAppBuilder withService(@NonNull final Service service) {
            services.add(service);
            return this;
        }

        public TestAppBuilder withHapiVersion(@NonNull final SemanticVersion version) {
            this.hapiVersion = version;
            return this;
        }

        public TestAppBuilder withSoftwareVersion(@NonNull final SemanticVersion version) {
            this.softwareVersion = version;
            return this;
        }

        public TestAppBuilder withConfigSource(@NonNull final ConfigSource source) {
            configBuilder.withSource(source);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, @Nullable final String value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, final boolean value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, final int value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, final long value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, final double value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, @NonNull final Object value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withNode(@NonNull final NodeInfo nodeInfo) {
            nodes.add(nodeInfo);
            return this;
        }

        public TestAppBuilder withSelfNode(@NonNull final NodeInfo selfNodeInfo) {
            this.selfNodeInfo = selfNodeInfo;
            return this;
        }

        public App build() {
            final var hederaSoftwareVersion = new HederaSoftwareVersion(this.hapiVersion, this.softwareVersion);

            final SelfNodeInfo realSelfNodeInfo;
            if (this.selfNodeInfo == null) {
                final var nodeSelfId = new NodeId(7);
                final var nodeSelfAccountId = AccountID.newBuilder()
                        .shardNum(0)
                        .realmNum(0)
                        .accountNum(8)
                        .build();
                realSelfNodeInfo = new SelfNodeInfoImpl(7, nodeSelfAccountId, false, "Node7", hederaSoftwareVersion);
            } else {
                realSelfNodeInfo = new SelfNodeInfoImpl(
                        selfNodeInfo.nodeId(),
                        selfNodeInfo.accountId(),
                        selfNodeInfo.zeroStake(),
                        selfNodeInfo.memo(),
                        hederaSoftwareVersion);
            }

            final var initialState = new FakeHederaState();
            final var workingStateAccessor = new WorkingStateAccessor();
            services.forEach(svc -> {
                final var reg = new FakeSchemaRegistry();
                svc.registerSchemas(reg);
                reg.migrate(svc.getServiceName(), initialState);
            });
            workingStateAccessor.setHederaState(initialState);

            final ConfigProvider configProvider = () -> new VersionedConfigImpl(configBuilder.getOrCreateConfig(), 1);
            final var addresses = nodes.stream()
                    .map(nodeInfo -> new Address()
                            .copySetNodeId(new NodeId(nodeInfo.nodeId()))
                            .copySetMemo(nodeInfo.memo())
                            .copySetWeight(nodeInfo.zeroStake() ? 0 : 10))
                    .toList();

            final var platform = new FakePlatform(realSelfNodeInfo.nodeId(), new AddressBook(addresses));
            final var networkInfo = new NetworkInfoImpl(realSelfNodeInfo, platform, configProvider);

            return new App() {
                @NonNull
                @Override
                public HederaSoftwareVersion softwareVersion() {
                    return hederaSoftwareVersion;
                }

                @NonNull
                @Override
                public WorkingStateAccessor workingStateAccessor() {
                    return workingStateAccessor;
                }

                @NonNull
                @Override
                public NetworkInfo networkInfo() {
                    return networkInfo;
                }

                @NonNull
                @Override
                public ConfigProvider configProvider() {
                    return configProvider;
                }

                @NonNull
                @Override
                public Platform platform() {
                    return platform;
                }

                @NonNull
                @Override
                public StateMutator stateMutator(@NonNull final String serviceName) {
                    final var fakeHederaState = requireNonNull(workingStateAccessor.getHederaState());
                    final var writableStates = (MapWritableStates) fakeHederaState.createWritableStates(serviceName);
                    return new StateMutator(writableStates);
                }
            };
        }
    }

    private static final class FakePlatform implements Platform {
        private final NodeId selfNodeId;
        private final AddressBook addressBook;

        private FakePlatform(long selfNodeId, AddressBook addressBook) {
            this.selfNodeId = new NodeId(selfNodeId);
            this.addressBook = addressBook;
        }

        @Override
        public PlatformContext getContext() {
            return null;
        }

        @Override
        public NotificationEngine getNotificationEngine() {
            return null;
        }

        @Override
        public Signature sign(byte[] bytes) {
            return null;
        }

        @Override
        public AddressBook getAddressBook() {
            return addressBook;
        }

        @Override
        public NodeId getSelfId() {
            return selfNodeId;
        }

        @Override
        public <T extends SwirldState> AutoCloseableWrapper<T> getLatestImmutableState(@NonNull String s) {
            return null;
        }

        @Override
        public <T extends SwirldState> AutoCloseableWrapper<T> getLatestSignedState(@NonNull String s) {
            return null;
        }

        @Override
        public boolean createTransaction(@NonNull byte[] bytes) {
            return false;
        }
    }
}
