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

package com.swirlds.virtual.merkle.reconnect;

import static com.swirlds.common.units.UnitConstants.BYTES_TO_BITS;
import static com.swirlds.common.units.UnitConstants.MEBIBYTES_TO_BYTES;
import static com.swirlds.test.framework.TestQualifierTags.TIME_CONSUMING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.swirlds.common.config.singleton.ConfigurationHolder;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.common.test.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.merkle.util.MerkleTestUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.test.framework.config.TestConfigBuilder;
import com.swirlds.virtual.merkle.TestKey;
import com.swirlds.virtual.merkle.TestValue;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Virtual Map MerkleDB Reconnect Test")
class VirtualMapMerkleDbReconnectTest extends VirtualMapMerkleDbReconnectTestBase {

    @BeforeAll
    static void beforeAll() throws Exception {
        final Configuration config = new TestConfigBuilder()
                .withValue("merkleDb.keySetBloomFilterSizeInBytes", 2 * MEBIBYTES_TO_BYTES * BYTES_TO_BITS)
                .withValue("merkleDb.keySetHalfDiskHashMapSize", "10000")
                .withValue("merkleDb.keySetHalfDiskHashMapBuffer", "1000")
                .getOrCreateConfig();

        ConfigurationHolder.getInstance().setConfiguration(config);
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.1")})
    @DisplayName("Empty teacher and empty learner")
    void emptyTeacherAndLearner() {
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.2")})
    @DisplayName("Empty teacher and full learner")
    void emptyTeacherFullLearner() {
        learnerMap.put(A_KEY, APPLE);
        learnerMap.put(B_KEY, BANANA);
        learnerMap.put(C_KEY, CHERRY);
        learnerMap.put(D_KEY, DATE);
        learnerMap.put(E_KEY, EGGPLANT);
        learnerMap.put(F_KEY, FIG);
        learnerMap.put(G_KEY, GRAPE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.3")})
    @DisplayName("Full teacher and empty learner")
    void fullTeacherEmptyLearner() {
        teacherMap.put(A_KEY, APPLE);
        teacherMap.put(B_KEY, BANANA);
        teacherMap.put(C_KEY, CHERRY);
        teacherMap.put(D_KEY, DATE);
        teacherMap.put(E_KEY, EGGPLANT);
        teacherMap.put(F_KEY, FIG);
        teacherMap.put(G_KEY, GRAPE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.4")})
    @DisplayName("Single-leaf teacher and empty learner")
    void singleLeafTeacherEmptyLearner() {
        teacherMap.put(A_KEY, APPLE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.5")})
    @DisplayName("Empty teacher and single leaf learner")
    void emptyTeacherSingleLeafLearner() {
        learnerMap.put(A_KEY, APPLE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.6")})
    @DisplayName("Two-leaf teacher and empty learner")
    void twoLeafTeacherEmptyLearner() {
        teacherMap.put(A_KEY, APPLE);
        teacherMap.put(B_KEY, BANANA);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.7")})
    @DisplayName("Empty teacher and two-leaf learner")
    void emptyTeacherTwoLeafLearner() {
        learnerMap.put(A_KEY, APPLE);
        learnerMap.put(B_KEY, BANANA);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.8")})
    @DisplayName("Teacher and Learner that are the same size but completely different")
    void equalFullTeacherFullLearner() {
        teacherMap.put(A_KEY, AARDVARK);
        teacherMap.put(B_KEY, BEAR);
        teacherMap.put(C_KEY, CUTTLEFISH);
        teacherMap.put(D_KEY, DOG);
        teacherMap.put(E_KEY, EMU);
        teacherMap.put(F_KEY, FOX);
        teacherMap.put(G_KEY, GOOSE);

        learnerMap.put(A_KEY, APPLE);
        learnerMap.put(B_KEY, BANANA);
        learnerMap.put(C_KEY, CHERRY);
        learnerMap.put(D_KEY, DATE);
        learnerMap.put(E_KEY, EGGPLANT);
        learnerMap.put(F_KEY, FIG);
        learnerMap.put(G_KEY, GRAPE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.9")})
    @DisplayName("Equivalent teacher and learner that are full")
    void sameSizeFullTeacherFullLearner() {
        teacherMap.put(A_KEY, APPLE);
        teacherMap.put(B_KEY, BANANA);
        teacherMap.put(C_KEY, CHERRY);
        teacherMap.put(D_KEY, DATE);
        teacherMap.put(E_KEY, EGGPLANT);
        teacherMap.put(F_KEY, FIG);
        teacherMap.put(G_KEY, GRAPE);

        learnerMap.put(A_KEY, APPLE);
        learnerMap.put(B_KEY, BANANA);
        learnerMap.put(C_KEY, CHERRY);
        learnerMap.put(D_KEY, DATE);
        learnerMap.put(E_KEY, EGGPLANT);
        learnerMap.put(F_KEY, FIG);
        learnerMap.put(G_KEY, GRAPE);

        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.10")})
    @DisplayName("Single leaf teacher and full learner where the leaf is the same")
    void singleLeafTeacherFullLearner() {
        teacherMap.put(A_KEY, APPLE);

        learnerMap.put(A_KEY, APPLE);
        learnerMap.put(B_KEY, BANANA);
        learnerMap.put(C_KEY, CHERRY);
        learnerMap.put(D_KEY, DATE);
        learnerMap.put(E_KEY, EGGPLANT);
        learnerMap.put(F_KEY, FIG);
        learnerMap.put(G_KEY, GRAPE);

        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.11")})
    @DisplayName("Single leaf teacher and full learner where the leaf differs")
    void singleLeafTeacherFullLearner2() {
        teacherMap.put(A_KEY, AARDVARK);

        learnerMap.put(A_KEY, APPLE);
        learnerMap.put(B_KEY, BANANA);
        learnerMap.put(C_KEY, CHERRY);
        learnerMap.put(D_KEY, DATE);
        learnerMap.put(E_KEY, EGGPLANT);
        learnerMap.put(F_KEY, FIG);
        learnerMap.put(G_KEY, GRAPE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.12")})
    @DisplayName("Full teacher and single-leaf learner where the leaf is equivalent")
    void fullTeacherSingleLeafLearner() {
        teacherMap.put(A_KEY, AARDVARK);
        teacherMap.put(B_KEY, BEAR);
        teacherMap.put(C_KEY, CUTTLEFISH);
        teacherMap.put(D_KEY, DOG);
        teacherMap.put(E_KEY, EMU);
        teacherMap.put(F_KEY, FOX);
        teacherMap.put(G_KEY, GOOSE);

        learnerMap.put(A_KEY, AARDVARK);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-003"), @Tag("VMAP-003.13")})
    @DisplayName("Full teacher and single-leaf learner where the leaf differs")
    void fullTeacherSingleLeafLearner2() {
        teacherMap.put(A_KEY, AARDVARK);
        teacherMap.put(B_KEY, BEAR);
        teacherMap.put(C_KEY, CUTTLEFISH);
        teacherMap.put(D_KEY, DOG);
        teacherMap.put(E_KEY, EMU);
        teacherMap.put(F_KEY, FOX);
        teacherMap.put(G_KEY, GOOSE);

        learnerMap.put(A_KEY, APPLE);
        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect")})
    @DisplayName("Teacher is requested to stop teaching after a few attempts")
    void simulateTeacherFallenBehind() {
        teacherMap.put(A_KEY, APPLE);
        teacherMap.put(B_KEY, BANANA);
        teacherMap.put(C_KEY, CHERRY);
        teacherMap.put(D_KEY, DATE);
        teacherMap.put(E_KEY, EGGPLANT);
        teacherMap.put(F_KEY, FIG);

        final AtomicInteger counter = new AtomicInteger(0);
        requestTeacherToStop = () -> counter.incrementAndGet() == 4;

        reconnectMultipleTimes(2);
    }

    /**
     * This test simulates some divergence from the teacher and the learner. At the time both the teacher and learner
     * had diverged, both had simple integer values for the key and value. At the time of divergence, the teacher had
     * some percentage of keys with an updated value (1 million + the old value). After reconnect, the learner should
     * get those new values.
     * <p>
     * The added wrinkle here is that we want some changes to be in the cache on the learner's side. We don't want
     * everything in the database. So we will add things in batches to different learner copies over time.
     */
    @ParameterizedTest
    @CsvSource({
        "10000,300,1", "10000,300,10", "10000,300,60", "10000,300,90",
        "100000,2500,1", "100000,2500,10", "100000,2500,60", "100000,2500,90"
    })
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect"), @Tag("VMAP-027")})
    @Tag(TIME_CONSUMING)
    @DisplayName("Reconnect two trees of the same size with some leaves clean and some leaves in cache")
    void reconnectWithSomeLeavesCleanAndSomeInCache(final int max, final int batches, final int chance) {
        final Random rand = new Random(8);

        for (int i = 0; i < max; i++) {
            final boolean makeDifferent = rand.nextInt(100) < chance; // % chance of a changed leaf
            final int value = makeDifferent ? i + 1_000_000 : i;
            teacherMap.put(new TestKey(i), new TestValue(value));
        }

        for (int i = 0; i < max; i++) {
            if (i > 0 && i % batches == 0) {
                final var oldMap = learnerMap;
                learnerMap = learnerMap.copy();
                oldMap.release();
            }
            learnerMap.put(new TestKey(i), new TestValue(i));
        }

        assertDoesNotThrow(this::reconnect, "Should not throw a Exception");
        System.out.println("Looking for broken children");
        findBrokenChildren(learnerMap);
    }

    /**
     * Configure reconnect so that failed reconnect attempts abort very quickly.
     */
    private void configureReconnectToFailQuickly() {
        new TestConfigBuilder()
                .withValue("reconnect.active", "true")
                .withValue("reconnect.reconnectWindowSeconds", "0")
                .withValue("reconnect.fallenBehindThreshold", "0")
                // This is important! A low value will cause a failed reconnect to finish more quicly.
                .withValue("reconnect.asyncStreamTimeout", "500ms")
                .withValue("reconnect.asyncOutputStreamFlush", "10ms")
                .withValue("reconnect.asyncStreamBufferSize", "1000")
                .withValue("reconnect.maximumReconnectFailuresBeforeShutdown", "0")
                .withValue("reconnect.minimumTimeBetweenReconnects", "0s")
                .getOrCreateConfig();
    }

    private void buildReconnectMaps(final TreePermutation treePermutation) {
        for (int i = treePermutation.teacherStart; i < treePermutation.teacherEnd; i++) {
            teacherMap.put(new TestKey(i), new TestValue(i));
        }

        for (int i = treePermutation.learnerStart; i < treePermutation.learnerEnd; i++) {
            learnerMap.put(new TestKey(i), new TestValue(i));
        }
    }

    @ParameterizedTest
    @MethodSource("provideSmallTreePermutations")
    @DisplayName("Learner Aborts Reconnect On First Operation")
    @Tag(TIME_CONSUMING)
    void learnerAbortsReconnectOnFirstOperation(final TreePermutation treePermutation) {
        configureReconnectToFailQuickly();

        buildReconnectMaps(treePermutation);

        learnerBuilder.setNumCallsBeforeThrow(0);
        learnerBuilder.setNumTimesToBreak(4);

        reconnectMultipleTimes(5);
    }

    @ParameterizedTest
    @MethodSource("provideSmallTreePermutations")
    @DisplayName("Learner Aborts Reconnect Half Way Through")
    void learnerAbortsReconnectHalfWayThrough(final TreePermutation treePermutation) {
        configureReconnectToFailQuickly();

        buildReconnectMaps(treePermutation);

        learnerBuilder.setNumCallsBeforeThrow((treePermutation.teacherEnd - treePermutation.teacherStart) / 2);
        learnerBuilder.setNumTimesToBreak(4);

        reconnectMultipleTimes(5);
    }

    @ParameterizedTest
    @MethodSource("provideSmallTreePermutations")
    @DisplayName("Learner Aborts Reconnect On Last Operation")
    @Tag(TIME_CONSUMING)
    void learnerAbortsReconnectOnLastOperation(final TreePermutation treePermutation) {
        configureReconnectToFailQuickly();

        buildReconnectMaps(treePermutation);

        learnerBuilder.setNumCallsBeforeThrow(Math.min(treePermutation.teacherEnd, treePermutation.learnerEnd));
        learnerBuilder.setNumTimesToBreak(4);

        reconnectMultipleTimes(5);
    }

    private Function<VirtualMap<TestKey, TestValue>, MerkleNode> buildBadTeacherTreeBuilder(
            final int permittedLeaves, final int permittedInternals) {
        return (final VirtualMap<TestKey, TestValue> map) -> {
            // We need to hash the original tree before getting its view.
            MerkleCryptoFactory.getInstance().digestTreeSync(map);

            final MerkleInternal imitationMap = new FakeVirtualMap();

            imitationMap.setChild(0, map.getChild(0).copy());

            final TeacherTreeView<Long> view = ((VirtualRootNode<?, ?>) map.getChild(1)).buildTeacherView();
            final TeacherTreeView<Long> badView =
                    new BrokenVirtualMapTeacherView(view, permittedInternals, permittedLeaves);
            final MerkleNode imitationRoot = new FakeVirtualRootNode(badView);
            imitationMap.setChild(1, imitationRoot);

            return imitationMap;
        };
    }

    @ParameterizedTest
    @MethodSource("provideSmallTreePermutations")
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect")})
    @DisplayName("Teacher Aborts Reconnect On First Internal")
    @Tag(TIME_CONSUMING)
    void teacherAbortsReconnectOnFirstInternal(final TreePermutation treePermutation) {

        configureReconnectToFailQuickly();

        buildReconnectMaps(treePermutation);

        final int permittedLeaves = Integer.MAX_VALUE;
        final int permittedInternals = 0;

        reconnectMultipleTimes(5, buildBadTeacherTreeBuilder(permittedLeaves, permittedInternals));
    }

    @ParameterizedTest
    @MethodSource("provideSmallTreePermutations")
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect")})
    @DisplayName("Teacher Aborts Reconnect On Last Internal")
    @Tag(TIME_CONSUMING)
    void teacherAbortsReconnectOnLastInternal(final TreePermutation treePermutation) {

        configureReconnectToFailQuickly();

        buildReconnectMaps(treePermutation);

        final int permittedLeaves = Integer.MAX_VALUE;
        final int permittedInternals = (treePermutation.teacherEnd - treePermutation.teacherStart) - 2;

        reconnectMultipleTimes(5, buildBadTeacherTreeBuilder(permittedLeaves, permittedInternals));
    }

    @ParameterizedTest
    @MethodSource("provideSmallTreePermutations")
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect")})
    @DisplayName("Teacher Aborts Reconnect On First Leaf")
    @Tag(TIME_CONSUMING)
    void teacherAbortsReconnectOnFirstLeaf(final TreePermutation treePermutation) {

        configureReconnectToFailQuickly();

        buildReconnectMaps(treePermutation);

        final int permittedLeaves = 0;
        final int permittedInternals = Integer.MAX_VALUE;

        reconnectMultipleTimes(5, buildBadTeacherTreeBuilder(permittedLeaves, permittedInternals));
    }

    @ParameterizedTest
    @MethodSource("provideSmallTreePermutations")
    @Tags({@Tag("VirtualMerkle"), @Tag("Reconnect")})
    @DisplayName("Teacher Aborts Reconnect On Last Leaf")
    @Tag(TIME_CONSUMING)
    void teacherAbortsReconnectOnLastLeaf(final TreePermutation treePermutation) {

        configureReconnectToFailQuickly();

        buildReconnectMaps(treePermutation);

        final int permittedLeaves = 0;
        final int permittedInternals = (treePermutation.teacherEnd - treePermutation.teacherStart) - 1;

        reconnectMultipleTimes(5, buildBadTeacherTreeBuilder(permittedLeaves, permittedInternals));
    }

    /**
     * Describes a permutation of two virtual maps to be used during a reconnect test.
     *
     * @param teacherStart the id of the teacher's first leaf
     * @param teacherEnd   the id of the teacher's last leaf
     * @param learnerStart the ID of the learner's first leaf
     * @param learnerEnd   the ID of the learner's last leaf
     */
    private record TreePermutation(
            String description, int teacherStart, int teacherEnd, int learnerStart, int learnerEnd) {

        @Override
        public String toString() {
            return description;
        }
    }

    static Stream<Arguments> provideSmallTreePermutations() {
        final List<Arguments> args = new ArrayList<>();
        args.add(Arguments.of(
                new TreePermutation("Two large leaf trees that have no intersection", 0, 1_000, 1_000, 2_000)));
        args.add(Arguments.of(new TreePermutation("Two large leaf trees that intersect", 0, 1_000, 500, 1_500)));
        args.add(Arguments.of(new TreePermutation(
                "A smaller tree (teacher) and larger tree (learner) that do not intersect", 0, 10, 1_000, 2_000)));
        args.add(Arguments.of(new TreePermutation(
                "A smaller tree (learner) and larger tree (teacher) that do not intersect", 1_000, 2_000, 0, 10)));
        args.add(Arguments.of(new TreePermutation(
                "A smaller tree (teacher) and larger tree (learner) that do intersect", 0, 10, 5, 1_005)));
        args.add(Arguments.of(new TreePermutation(
                "A smaller tree (learner) and larger tree (teacher) that do intersect", 5, 1_005, 0, 10)));
        args.add(Arguments.of(new TreePermutation("Two hundred leaf trees (v1) that intersect", 50, 250, 0, 100)));
        args.add(Arguments.of(new TreePermutation("Two hundred leaf trees (v2) that intersect", 50, 249, 0, 100)));
        args.add(Arguments.of(new TreePermutation("Two hundred leaf trees (v3) that intersect", 50, 251, 0, 100)));
        return args.stream();
    }

    private static void findBrokenChildren(final MerkleNode root) {

        final Queue<MerkleInternal> queue = new LinkedList<>();

        if (root instanceof MerkleInternal) {
            queue.add(root.asInternal());
        }

        while (!queue.isEmpty()) {

            final MerkleInternal parent = queue.remove();

            for (int childIndex = 0; childIndex < parent.getNumberOfChildren(); childIndex++) {
                try {
                    final MerkleNode child = parent.getChild(childIndex);
                    if (child instanceof MerkleInternal) {
                        queue.add(child.asInternal());
                    }
                } catch (final Exception e) {
                    System.err.println("unable to fetch child with index " + childIndex + " from "
                            + parent.getClass().getSimpleName() + " @ " + parent.getRoute());
                }
            }
        }
    }

    @Test
    @DisplayName("Delete Already Deleted Account")
    void deleteAlreadyDeletedAccount() throws Exception {
        teacherMap.put(A_KEY, AARDVARK);
        teacherMap.put(B_KEY, BEAR);
        teacherMap.put(C_KEY, CUTTLEFISH);

        learnerMap.put(A_KEY, AARDVARK);
        learnerMap.put(B_KEY, BEAR);
        learnerMap.put(C_KEY, CUTTLEFISH); // leaf path value is 4

        // maps / caches should be identical at this point.  But now
        // remove a key (and add another) from the teacher, before reconnect starts.
        teacherMap.remove(C_KEY);
        teacherMap.put(D_KEY, DOG);

        final MerkleInternal teacherTree = createTreeForMap(teacherMap);
        final VirtualMap<TestKey, TestValue> copy = teacherMap.copy();
        final MerkleInternal learnerTree = createTreeForMap(learnerMap);

        // reconnect happening
        DummyMerkleInternal afterSyncLearnerTree = MerkleTestUtils.hashAndTestSynchronization(learnerTree, teacherTree);

        // not sure what is the better way to get the embedded Virtual map
        DummyMerkleInternal node = afterSyncLearnerTree.getChild(1);
        VirtualMap<TestKey, TestValue> afterMap = node.getChild(3);

        assertEquals(DOG, afterMap.get(D_KEY), "After sync, should have D_KEY available");
        assertNull(afterMap.get(C_KEY), "After sync, should not have C_KEY anymore");

        afterSyncLearnerTree.release();
        copy.release();
        teacherTree.release();
        learnerTree.release();
    }
}
