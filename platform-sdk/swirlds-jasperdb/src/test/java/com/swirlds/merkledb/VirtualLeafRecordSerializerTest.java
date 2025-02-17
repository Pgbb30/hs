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

package com.swirlds.merkledb;

import static com.swirlds.jasperdb.utilities.HashTools.DEFAULT_DIGEST;
import static com.swirlds.merkledb.ExampleFixedSizeVirtualValue.RANDOM_BYTES;
import static com.swirlds.merkledb.MerkleDbTestUtils.randomString;
import static com.swirlds.merkledb.serialize.BaseSerializer.VARIABLE_DATA_SIZE;
import static org.apache.commons.lang3.RandomUtils.nextInt;
import static org.apache.commons.lang3.RandomUtils.nextLong;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.HashBuilder;
import com.swirlds.merkledb.files.VirtualLeafRecordSerializer;
import com.swirlds.merkledb.serialize.KeySerializer;
import com.swirlds.merkledb.serialize.ValueSerializer;
import com.swirlds.virtualmap.VirtualLongKey;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@SuppressWarnings("unchecked")
class VirtualLeafRecordSerializerTest {

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds.merkledb");
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    @SuppressWarnings("rawtypes")
    void testEquals(final TestType testType) {
        final KeySerializer<?> keySerializer = testType.dataType().getKeySerializer();
        final ValueSerializer<?> valueSerializer = testType.dataType().getValueSerializer();

        final MerkleDbTableConfig<VirtualLongKey, ExampleFixedSizeVirtualValue> tableConfig1 = new MerkleDbTableConfig(
                (short) 1,
                DigestType.SHA_384,
                (short) keySerializer.getCurrentDataVersion(),
                keySerializer,
                (short) valueSerializer.getCurrentDataVersion(),
                valueSerializer);
        final VirtualLeafRecordSerializer<VirtualLongKey, ExampleFixedSizeVirtualValue> virtualLeafRecordSerializer1 =
                new VirtualLeafRecordSerializer<>(tableConfig1);

        final MerkleDbTableConfig<VirtualLongKey, ExampleFixedSizeVirtualValue> tableConfig2 = new MerkleDbTableConfig(
                (short) 1,
                DigestType.SHA_384,
                (short) keySerializer.getCurrentDataVersion(),
                keySerializer,
                (short) valueSerializer.getCurrentDataVersion(),
                valueSerializer);
        final VirtualLeafRecordSerializer<VirtualLongKey, ExampleFixedSizeVirtualValue> virtualLeafRecordSerializer2 =
                new VirtualLeafRecordSerializer<>(tableConfig2);

        assertEquals(
                virtualLeafRecordSerializer1,
                virtualLeafRecordSerializer2,
                "Two identical VirtualLeafRecordSerializers did not equal each other");
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void testSerializedSize(final TestType testType) {
        final var serializer = createSerializer(testType);
        int expectedSerializedSize = testType.fixedSize
                ? testType.dataType().getKeySerializer().getSerializedSize()
                        + testType.dataType().getValueSerializer().getSerializedSize()
                        + Long.BYTES
                : -1;
        assertEquals(expectedSerializedSize, serializer.getSerializedSize());
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void testHeaderSize(final TestType testType) {
        final var serializer = createSerializer(testType);
        int expectedSerializedSize = testType.fixedSize ? Long.BYTES : Long.BYTES + Integer.BYTES;
        assertEquals(expectedSerializedSize, serializer.getHeaderSize());
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    void testSerializeDeserialize(final TestType testType) throws IOException {
        final VirtualLeafRecord<VirtualLongKey, ExampleByteArrayVirtualValue> record =
                testType.dataType().createVirtualLeafRecord(nextLong(), nextInt(), nextInt());

        final var serializer = createSerializer(testType);
        final ByteBuffer buffer = ByteBuffer.allocate(2048);
        serializer.serialize(record, buffer);

        buffer.flip();

        final VirtualLeafRecord<VirtualLongKey, ExampleByteArrayVirtualValue> deserializedRecord =
                serializer.deserialize(buffer, serializer.getCurrentDataVersion());
        assertEquals(record, deserializedRecord, "Deserialized record did not match original record");
    }

    /**
     * This test emulates a record that was serialized with a hash
     */
    @Test
    void testDeserializeWithHash() throws IOException {
        VirtualLeafRecordSerializer<VirtualLongKey, ExampleByteArrayVirtualValue> serializer =
                createSerializer(TestType.fixed_fixed);

        // emulate a serialized record
        final ByteBuffer buffer = ByteBuffer.allocate(1024);
        final long path = nextLong();
        final long key = nextLong();
        // RANDOM_BYTES is the size of ExampleFixedSizeVirtualValue data
        final String value = randomString(RANDOM_BYTES, new Random());
        buffer.putLong(path);
        Hash hash = new HashBuilder(DEFAULT_DIGEST).update(nextInt()).build();
        buffer.put(hash.getValue());
        buffer.putLong(key);
        int id = nextInt();
        buffer.putInt(id); // value id
        buffer.put(value.getBytes());

        buffer.flip();

        VirtualLeafRecord<VirtualLongKey, ExampleByteArrayVirtualValue> record =
                serializer.deserialize(buffer, serializer.getCurrentDataVersion() | 0x1);

        assertEquals(path, record.getPath());
        assertEquals(key, record.getKey().getKeyAsLong());
        assertEquals(value, new String(record.getValue().getData()));
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    public void testGetSerializedSizeForVersionForFixedSize_noHash(final TestType testType) {
        long version = ((long) nextInt(1, 100) << 16) | ((long) nextInt(1, 100) << 32);
        VirtualLeafRecordSerializer<VirtualLongKey, ExampleByteArrayVirtualValue> serializer =
                createSerializer(testType);
        if (testType.fixedSize) {
            assertEquals(
                    Long.BYTES
                            + testType.dataType().getKeySerializer().getSerializedSize()
                            + testType.dataType().getValueSerializer().getSerializedSize(),
                    serializer.getSerializedSizeForVersion(version));
        } else {
            assertEquals(VARIABLE_DATA_SIZE, serializer.getSerializedSizeForVersion(version));
        }
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    public void testGetSerializedSizeForVersionForFixedSize_withHash(final TestType testType) {
        long version = ((long) nextInt(1, 100) << 16) | ((long) nextInt(1, 100) << 32) | 0x1;
        VirtualLeafRecordSerializer<VirtualLongKey, ExampleByteArrayVirtualValue> serializer =
                createSerializer(testType);
        if (testType.fixedSize) {
            assertEquals(
                    Long.BYTES
                            + testType.dataType().getKeySerializer().getSerializedSize()
                            + testType.dataType().getValueSerializer().getSerializedSize()
                            + DEFAULT_DIGEST.digestLength(),
                    serializer.getSerializedSizeForVersion(version));
        } else {
            assertEquals(VARIABLE_DATA_SIZE, serializer.getSerializedSizeForVersion(version));
        }
    }

    private static VirtualLeafRecordSerializer<VirtualLongKey, ExampleByteArrayVirtualValue> createSerializer(
            TestType testType) {
        final KeySerializer<?> keySerializer = testType.dataType().getKeySerializer();
        final ValueSerializer<?> valueSerializer = testType.dataType().getValueSerializer();

        final MerkleDbTableConfig<VirtualLongKey, ExampleByteArrayVirtualValue> tableConfig1 = new MerkleDbTableConfig(
                (short) 1,
                DigestType.SHA_384,
                (short) keySerializer.getCurrentDataVersion(),
                keySerializer,
                (short) valueSerializer.getCurrentDataVersion(),
                valueSerializer);
        return new VirtualLeafRecordSerializer<>(tableConfig1);
    }
}
