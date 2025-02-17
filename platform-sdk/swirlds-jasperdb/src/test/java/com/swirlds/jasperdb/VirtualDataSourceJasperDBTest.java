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

package com.swirlds.jasperdb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyFalse;
import static com.swirlds.merkledb.MerkleDbTestUtils.checkDirectMemoryIsCleanedUpToLessThanBaseUsage;
import static com.swirlds.merkledb.MerkleDbTestUtils.getDirectMemoryUsedBytes;
import static com.swirlds.merkledb.MerkleDbTestUtils.hash;
import static com.swirlds.merkledb.MerkleDbTestUtils.shuffle;
import static com.swirlds.virtualmap.datasource.VirtualDataSource.INVALID_PATH;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.TemporaryFileBuilder;
import com.swirlds.common.units.UnitConstants;
import com.swirlds.jasperdb.files.hashmap.KeyIndexType;
import com.swirlds.test.framework.TestQualifierTags;
import com.swirlds.virtualmap.VirtualLongKey;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class VirtualDataSourceJasperDBTest {

    private static final int COUNT = 10_000;
    private static final Random RANDOM = new Random(1234);

    private static Path testDirectory;

    @BeforeAll
    static void setup() throws Exception {
        testDirectory = TemporaryFileBuilder.buildTemporaryFile("VirtualDataSourceJasperDBTest");
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds.jasperdb");
    }

    /**
     * Keep track of initial direct memory used already, so we can check if we leek over and above
     * what we started with
     */
    private long directMemoryUsedAtStart;

    @BeforeEach
    void initializeDirectMemoryAtStart() {
        directMemoryUsedAtStart = getDirectMemoryUsedBytes();
    }

    @AfterEach
    void checkDirectMemoryForLeaks() {
        // check all memory is freed after DB is closed
        assertTrue(
                checkDirectMemoryIsCleanedUpToLessThanBaseUsage(directMemoryUsedAtStart),
                "Direct Memory used is more than base usage even after 20 gc() calls. At start was "
                        + (directMemoryUsedAtStart * UnitConstants.BYTES_TO_MEBIBYTES)
                        + "MB and is now "
                        + (getDirectMemoryUsedBytes() * UnitConstants.BYTES_TO_MEBIBYTES)
                        + "MB");
    }

    // =================================================================================================================
    // Tests

    @ParameterizedTest
    @MethodSource("provideParameters")
    void createAndCheckInternalNodeHashes(final TestType testType, final int hashesRamToDiskThreshold)
            throws IOException {

        final String tableName = "createAndCheckInternalNodeHashes";
        // check db count
        assertEventuallyEquals(
                0L, VirtualDataSourceJasperDB::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected no open dbs");
        // create db
        final int count = 10_000;
        final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource =
                createDataSource(testDirectory, tableName, testType, count, hashesRamToDiskThreshold);
        // check db count
        assertEventuallyEquals(
                1L, VirtualDataSourceJasperDB::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected only 1 db");

        // create some node hashes
        dataSource.saveRecords(
                count,
                count * 2,
                IntStream.range(0, count).mapToObj(VirtualDataSourceJasperDBTest::createVirtualInternalRecord),
                Stream.empty(),
                Stream.empty());

        // check all the node hashes
        for (int i = 0; i < count; i++) {
            final var hash = dataSource.loadHash(i);
            assertEquals(hash(i), hash, "The hash for [" + i + "] should not have changed since it was created");
        }

        final IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> dataSource.loadHash(-1),
                "loadInternalRecord should throw IAE on invalid path");
        assertEquals("path is less than 0", e.getMessage(), "Detail message should capture the failure");

        // close data source
        dataSource.closeAndDelete();
        // check db count
        assertEventuallyEquals(
                0L, VirtualDataSourceJasperDB::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected no open dbs");
        // check the database was deleted
        assertEventuallyFalse(
                () -> Files.exists(testDirectory.resolve(tableName)),
                Duration.ofSeconds(1),
                "Database should have been deleted by closeAndDelete()");
    }

    private static Stream<Arguments> provideParameters() {
        final ArrayList<Arguments> arguments = new ArrayList<>(TestType.values().length * 3);
        final int[] ramDiskSplitOptions = new int[] {0, COUNT / 2, Integer.MAX_VALUE};
        for (final TestType testType : TestType.values()) {
            for (final int ramDiskSplit : ramDiskSplitOptions) {
                arguments.add(Arguments.of(testType, ramDiskSplit, false));
                arguments.add(Arguments.of(testType, ramDiskSplit, true));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void testRandomHashUpdates(final TestType testType) throws IOException {
        final int testSize = 1000;

        final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource =
                createDataSource(testDirectory, "test2", testType, testSize);
        try {
            // create some node hashes
            dataSource.saveRecords(
                    testSize,
                    testSize * 2,
                    IntStream.range(0, testSize).mapToObj(VirtualDataSourceJasperDBTest::createVirtualInternalRecord),
                    Stream.empty(),
                    Stream.empty());
            // create 4 lists with random hash updates some *10 hashes
            final IntArrayList[] lists = new IntArrayList[3];
            for (int i = 0; i < lists.length; i++) {
                lists[i] = new IntArrayList();
            }
            IntStream.range(0, testSize).forEach(i -> lists[RANDOM.nextInt(lists.length)].add(i));
            for (final IntArrayList list : lists) {
                dataSource.saveRecords(
                        testSize,
                        testSize * 2,
                        list.primitiveStream().mapToObj(i -> new VirtualHashRecord(i, hash(i * 10))),
                        Stream.empty(),
                        Stream.empty());
            }
            // check all the node hashes
            IntStream.range(0, testSize).forEach(i -> {
                try {
                    assertEquals(
                            hash(i * 10),
                            dataSource.loadHash(i),
                            "Internal hashes should not have changed since they were created");
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            // close data source
            dataSource.closeAndDelete();
            System.exit(1);
        } finally {
            // close data source
            dataSource.closeAndDelete();
        }
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void createAndCheckLeaves(final TestType testType) throws IOException {
        final int count = 10_000;
        final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource =
                createDataSource(testDirectory, "test3", testType, count);
        // create some leaves
        dataSource.saveRecords(
                count,
                count * 2,
                IntStream.range(count, count * 2).mapToObj(VirtualDataSourceJasperDBTest::createVirtualInternalRecord),
                IntStream.range(count, count * 2)
                        .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                Stream.empty());
        // check all the leaf data
        IntStream.range(count, count * 2).forEach(i -> assertLeaf(testType, dataSource, i, i));

        // invalid path should throw an exception
        assertThrows(
                IllegalArgumentException.class,
                () -> dataSource.loadLeafRecord(INVALID_PATH),
                "Loading a leaf record from invalid path should throw Exception");

        final IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> dataSource.loadHash(-1), "Loading a negative path should fail");
        assertEquals("path is less than 0", e.getMessage(), "Detail message should capture the failure");

        // close data source
        dataSource.closeAndDelete();
    }

    @ParameterizedTest
    @Tag(TestQualifierTags.TIME_CONSUMING)
    @EnumSource(TestType.class)
    void updateLeaves(final TestType testType) throws IOException, InterruptedException {
        final int incFirstLeafPath = 1;
        final int exclLastLeafPath = 1001;

        final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource =
                createDataSource(testDirectory, "test4", testType, exclLastLeafPath - incFirstLeafPath);
        // create some leaves
        dataSource.saveRecords(
                incFirstLeafPath,
                exclLastLeafPath,
                IntStream.range(incFirstLeafPath, exclLastLeafPath)
                        .mapToObj(VirtualDataSourceJasperDBTest::createVirtualInternalRecord),
                IntStream.range(incFirstLeafPath, exclLastLeafPath)
                        .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                Stream.empty());
        // check all the leaf data
        IntStream.range(incFirstLeafPath, exclLastLeafPath).forEach(i -> assertLeaf(testType, dataSource, i, i));
        // update all to i+10,000 in a random order
        final int[] randomInts = shuffle(
                RANDOM, IntStream.range(incFirstLeafPath, exclLastLeafPath).toArray());
        dataSource.saveRecords(
                incFirstLeafPath,
                exclLastLeafPath,
                Stream.empty(),
                Arrays.stream(randomInts)
                        .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i, i, i + 10_000))
                        .sorted(Comparator.comparingLong(VirtualLeafRecord::getPath)),
                Stream.empty());
        assertEquals(
                testType.dataType().createVirtualLeafRecord(100, 100, 100 + 10_000),
                testType.dataType().createVirtualLeafRecord(100, 100, 100 + 10_000),
                "same call to createVirtualLeafRecord returns different results");
        // check all the leaf data
        IntStream.range(incFirstLeafPath, exclLastLeafPath)
                .forEach(i -> assertLeaf(testType, dataSource, i, i, i + 10_000));
        // delete a couple leaves
        dataSource.saveRecords(
                incFirstLeafPath,
                exclLastLeafPath,
                Stream.empty(),
                Stream.empty(),
                IntStream.range(incFirstLeafPath + 10, incFirstLeafPath + 20)
                        .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)));
        // check deleted items are no longer there
        for (int i = (incFirstLeafPath + 10); i < (incFirstLeafPath + 20); i++) {
            final VirtualLongKey key = testType.dataType().createVirtualLongKey(i);
            assertEqualsAndPrint(null, dataSource.loadLeafRecord(key));
        }
        // check all remaining leaf data
        IntStream.range(incFirstLeafPath, incFirstLeafPath + 10)
                .forEach(i -> assertLeaf(testType, dataSource, i, i, i + 10_000));
        IntStream.range(incFirstLeafPath + 21, exclLastLeafPath)
                .forEach(i -> assertLeaf(testType, dataSource, i, i, i + 10_000));
        // close data source
        dataSource.closeAndDelete();

        // check db count
        assertEventuallyEquals(
                0L, VirtualDataSourceJasperDB::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected no open dbs");
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void moveLeaf(final TestType testType) throws IOException {
        final int incFirstLeafPath = 1;
        final int exclLastLeafPath = 1001;
        final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource =
                createDataSource(testDirectory, "test5", testType, exclLastLeafPath - incFirstLeafPath);
        // create some leaves
        dataSource.saveRecords(
                incFirstLeafPath,
                exclLastLeafPath,
                IntStream.range(incFirstLeafPath, exclLastLeafPath)
                        .mapToObj(VirtualDataSourceJasperDBTest::createVirtualInternalRecord),
                IntStream.range(incFirstLeafPath, exclLastLeafPath)
                        .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                Stream.empty());
        // check 250 and 500
        assertLeaf(testType, dataSource, 250, 250);
        assertLeaf(testType, dataSource, 500, 500);
        // move a leaf from 500 to 250, under new API there is no move as such, so we just write 500 leaf at 250 path
        final VirtualHashRecord vir500 = new VirtualHashRecord(
                testType.dataType().createVirtualInternalRecord(250).path(), hash(500));

        final VirtualLeafRecord<VirtualLongKey, ExampleByteArrayVirtualValue> vlr500 =
                testType.dataType().createVirtualLeafRecord(500);
        vlr500.setPath(250);
        dataSource.saveRecords(
                incFirstLeafPath, exclLastLeafPath, Stream.of(vir500), Stream.of(vlr500), Stream.empty());
        // check 250 now has 500's data
        assertLeaf(testType, dataSource, 700, 700);
        assertEquals(
                testType.dataType().createVirtualLeafRecord(500, 500, 500),
                dataSource.loadLeafRecord(500),
                "creating/loading same LeafRecord gives different results");
        assertLeaf(testType, dataSource, 250, 500);
        // close data source
        dataSource.closeAndDelete();
        // check db count
        assertEventuallyEquals(
                0L, VirtualDataSourceJasperDB::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected no open dbs");
    }

    @Test
    void preservesInterruptStatusWhenInterruptedSavingRecords() throws IOException, InterruptedException {
        final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource =
                createDataSource(testDirectory, "test6", TestType.fixed_fixed, 1000);

        final InterruptRememberingThread savingThread = slowRecordSavingThread(dataSource);

        savingThread.start();
        /* Don't interrupt until the saving thread will be blocked on the CountDownLatch,
         * awaiting all internal records to be written. */
        sleepUnchecked(100L);

        savingThread.interrupt();
        /* Give some time for the interrupt to set the thread's interrupt status */
        sleepUnchecked(100L);

        System.out.println("Checking interrupt count");
        assertEquals(
                2,
                savingThread.numInterrupts(),
                "Thread interrupt status should NOT be cleared (two total interrupts)");
        savingThread.join();
        // close data source
        dataSource.closeAndDelete();
        // check db count
        assertEventuallyEquals(
                0L, VirtualDataSourceJasperDB::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected no open dbs");
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void createCloseSnapshotCheckDelete(final TestType testType) throws IOException {
        final int count = 10_000;
        final String tableName = "testDB";
        final Path snapshotDbPath = prepareDataSourceFiles(testType, count, tableName);
        // reopen data source and check
        final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource2 =
                createDataSource(snapshotDbPath, tableName, testType, count);
        // check all the leaf data
        IntStream.range(count, count * 2).forEach(i -> assertLeaf(testType, dataSource2, i, i));
        // close data source
        dataSource2.closeAndDelete();
        // check db count
        assertEventuallyEquals(
                0L, VirtualDataSourceJasperDB::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected no open dbs");
    }

    private static Path prepareDataSourceFiles(TestType testType, int count, String tableName) throws IOException {
        final Path originalDbPath = testDirectory.resolve("jasperdb-" + testType);
        final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource =
                createDataSource(originalDbPath, tableName, testType, count);
        // create some leaves
        dataSource.saveRecords(
                count,
                count * 2,
                IntStream.range(count, count * 2)
                        .mapToObj(i -> testType.dataType().createVirtualInternalRecord(i)),
                IntStream.range(count, count * 2)
                        .mapToObj(i -> testType.dataType().createVirtualLeafRecord(i)),
                Stream.empty());
        // check all the leaf data
        IntStream.range(count, count * 2).forEach(i -> assertLeaf(testType, dataSource, i, i));
        // create a snapshot
        final Path snapshotDbPath = testDirectory.resolve("jasperdb-" + testType + "_SNAPSHOT");

        dataSource.snapshot(snapshotDbPath.resolve(tableName));
        // close data source
        dataSource.close();
        return snapshotDbPath;
    }

    @Test
    void testMissingMetadataFile() throws IOException {
        final TestType testType = TestType.fixed_fixed;
        final int count = 10_000;
        final String tableName = "testDB";
        final Path originalDbPath = testDirectory.resolve("jasperdb-" + testType);
        final Path snapshotDbPath = testDirectory.resolve("jasperdb-" + testType + "_SNAPSHOT");
        prepareDataSourceFiles(testType, count, tableName);
        // delete metadata file
        Files.delete(snapshotDbPath.resolve(tableName).resolve("metadata.jdbm"));
        // attempt to reopen data source and fail
        assertThrows(UncheckedIOException.class, () -> createDataSource(snapshotDbPath, tableName, testType, count));
        FileUtils.deleteDirectory(snapshotDbPath);
        FileUtils.deleteDirectory(originalDbPath);
    }

    @Test
    void testMetadataFileHasWrongVersion() throws IOException {
        final TestType testType = TestType.fixed_fixed;
        final int count = 10_000;
        final String tableName = "testDB";
        final Path originalDbPath = testDirectory.resolve("jasperdb-" + testType);
        final Path snapshotDbPath = testDirectory.resolve("jasperdb-" + testType + "_SNAPSHOT");
        FileUtils.deleteDirectory(snapshotDbPath);
        FileUtils.deleteDirectory(originalDbPath);
        prepareDataSourceFiles(testType, count, tableName);
        // delete metadata file
        FileChannel metadataFile = FileChannel.open(
                snapshotDbPath.resolve(tableName).resolve("metadata.jdbm"),
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        metadataFile.truncate(0);
        final ByteBuffer version = ByteBuffer.allocate(4);
        version.putInt(RANDOM.nextInt(100, 1000));
        version.flip();
        metadataFile.write(version);
        metadataFile.close();
        // attempt to reopen data source and fail
        assertThrows(UncheckedIOException.class, () -> createDataSource(snapshotDbPath, tableName, testType, count));
        FileUtils.deleteDirectory(snapshotDbPath);
        FileUtils.deleteDirectory(originalDbPath);
    }

    @Test
    @Tag(TestQualifierTags.TIME_CONSUMING)
    void preservesInterruptStatusWhenInterruptedClosing() throws IOException, InterruptedException {
        final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource =
                createDataSource(testDirectory, "test8", TestType.fixed_fixed, 1000);

        /* Keep an executor busy */
        final InterruptRememberingThread savingThread = slowRecordSavingThread(dataSource);
        savingThread.start();
        sleepUnchecked(100L);

        final InterruptRememberingThread closingThread = new InterruptRememberingThread(() -> {
            try {
                dataSource.close();
            } catch (final IOException ignore) {
            }
        });

        closingThread.start();
        closingThread.interrupt();
        sleepUnchecked(100L);

        System.out.println("Checking interrupt count for " + closingThread.getName());
        final var numInterrupts = closingThread.numInterrupts();
        assertEquals(2, numInterrupts, "Thread interrupt status should NOT be cleared (two total interrupts)");
        closingThread.join();
        savingThread.join();
        // close data source
        dataSource.closeAndDelete();
        // check db count
        assertEventuallyEquals(
                0L, VirtualDataSourceJasperDB::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected no open dbs");
    }

    @Test
    void canConstructWithOnDiskInternalHashStore() throws InterruptedException {
        final long finiteInMemHashThreshold = 1_000_000;
        assertDoesNotThrow(
                () -> createDataSource(testDirectory, "test9", TestType.fixed_fixed, 1000, finiteInMemHashThreshold)
                        .close(),
                "Should be possible to instantiate data source using on-disk internal hash store");

        // check db count
        assertEventuallyEquals(
                0L, VirtualDataSourceJasperDB::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected no open dbs");
    }

    @Test
    void canConstructWithNoRamInternalHashStore() {
        assertDoesNotThrow(
                () -> createDataSource(testDirectory, "test10", TestType.fixed_fixed, 1000, 0)
                        .close(),
                "Should be possible to instantiate data source with no in-memory internal hash store");
        // check db count
        assertEventuallyEquals(
                0L, VirtualDataSourceJasperDB::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected no open dbs");
    }

    @Test
    void canConstructStandardStoreWithMergingDisabled() {
        assertDoesNotThrow(
                () -> {
                    TestType.fixed_fixed
                            .dataType()
                            .createDataSource(testDirectory, "testDB", 1000, Long.MAX_VALUE, false, false)
                            .closeAndDelete();
                },
                "Should be possible to instantiate data source with merging disabled");
        // check db count
        assertEventuallyEquals(
                0L, VirtualDataSourceJasperDB::getCountOfOpenDatabases, Duration.ofSeconds(1), "Expected no open dbs");
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void testKeyIndexTypes(final TestType testType) throws Exception {
        final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource =
                createDataSource(testDirectory, "test11", testType, 1);

        try {
            assertEquals(
                    testType.dataType().getKeySerializer().getIndexType() == KeyIndexType.SEQUENTIAL_INCREMENTING_LONGS,
                    dataSource.isLongKeyMode(),
                    "Data source in expected long key mode.");
        } finally {
            // close data source
            dataSource.closeAndDelete();
        }
    }

    // =================================================================================================================
    // Helper Methods

    public static VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> createDataSource(
            final Path testDirectory, final String name, final TestType testType, final int size) throws IOException {
        return createDataSource(testDirectory, name, testType, size, Long.MAX_VALUE);
    }

    public static VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> createDataSource(
            final Path testDirectory,
            final String name,
            final TestType testType,
            final int size,
            final long hashesRamToDiskThreshold)
            throws IOException {
        return testType.dataType().createDataSource(testDirectory, name, size, hashesRamToDiskThreshold, false, false);
    }

    public static VirtualHashRecord createVirtualInternalRecord(final int i) {
        return new VirtualHashRecord(i, hash(i));
    }

    public static void assertLeaf(
            final TestType testType,
            final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource,
            final long path,
            final int i) {
        assertLeaf(testType, dataSource, path, i, i);
    }

    public static void assertLeaf(
            final TestType testType,
            final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource,
            final long path,
            final int i,
            final int valueIndex) {
        try {
            final VirtualLeafRecord<VirtualLongKey, ExampleByteArrayVirtualValue> expectedRecord =
                    testType.dataType().createVirtualLeafRecord(path, i, valueIndex);
            final VirtualLongKey key = testType.dataType().createVirtualLongKey(i);
            // things that should have changed
            assertEqualsAndPrint(expectedRecord, dataSource.loadLeafRecord(key));
            assertEqualsAndPrint(expectedRecord, dataSource.loadLeafRecord(path));
            assertEquals(hash(i), dataSource.loadHash(path), "unexpected Hash value");
        } catch (final Exception e) {
            e.printStackTrace();
            fail("Exception should not have been thrown here!");
        }
    }

    @SuppressWarnings("rawtypes")
    public static void assertEqualsAndPrint(final VirtualLeafRecord recordA, final VirtualLeafRecord recordB) {
        assertEquals(
                recordA == null ? null : recordA.toString(),
                recordB == null ? null : recordB.toString(),
                "Equal records should have the same toString representation");
    }

    private void sleepUnchecked(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException ignore) {
            /* No-op */
        }
    }

    private InterruptRememberingThread slowRecordSavingThread(
            final VirtualDataSourceJasperDB<VirtualLongKey, ExampleByteArrayVirtualValue> dataSource) {
        return new InterruptRememberingThread(() -> {
            try {
                dataSource.saveRecords(
                        1000,
                        2000,
                        IntStream.range(1, 5).mapToObj(i -> {
                            System.out.println("SLOWLY loading record #"
                                    + i
                                    + " in "
                                    + Thread.currentThread().getName());
                            sleepUnchecked(50L);
                            return createVirtualInternalRecord(i);
                        }),
                        null,
                        Stream.empty());
            } catch (final IOException impossible) {
                /* We don't throw this */
            }
        });
    }

    private static class InterruptRememberingThread extends Thread {
        private final AtomicInteger numInterrupts = new AtomicInteger(0);

        public InterruptRememberingThread(final Runnable target) {
            super(target);
        }

        @Override
        public void interrupt() {
            System.out.println(
                    this.getName() + " interrupted (that makes " + numInterrupts.incrementAndGet() + " times)");
            super.interrupt();
        }

        public synchronized int numInterrupts() {
            return numInterrupts.get();
        }
    }
}
