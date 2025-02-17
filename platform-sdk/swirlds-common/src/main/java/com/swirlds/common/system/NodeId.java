/*
 * Copyright (C) 2019-2023 Hedera Hashgraph, LLC
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

package com.swirlds.common.system;

import com.swirlds.common.io.SelfSerializable;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Objects;

/**
 * A class that is used to uniquely identify a Swirlds Node.
 */
public class NodeId implements Comparable<NodeId>, SelfSerializable {

    /** The class identifier for this class. */
    private static final long CLASS_ID = 0xea520dcf050bcaadL;

    /** The class version for this class. */
    private static final class ClassVersion {
        /**
         * The original version of the class.
         * @since 0.39.0
         */
        public static final int ORIGINAL = 1;
    }

    /** The undefined NodeId. */
    public static final NodeId UNDEFINED_NODE_ID = null;

    /** The first allowed Node ID. */
    public static final long LOWEST_NODE_NUMBER = 0L;

    /** The first NodeId. */
    public static final NodeId FIRST_NODE_ID = new NodeId(LOWEST_NODE_NUMBER);

    /** The ID number. */
    private long id;

    /**
     * Constructs an empty NodeId objects, used in deserialization only.
     */
    public NodeId() {}

    /**
     * Constructs a NodeId object with the given ID number.  The ID number must be non-negative.
     *
     * @param id the ID number
     * @throws IllegalArgumentException if the ID number is negative
     */
    public NodeId(final long id) {
        if (id < LOWEST_NODE_NUMBER) {
            throw new IllegalArgumentException("id must be non-negative");
        }
        this.id = id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.ORIGINAL;
    }

    /**
     * Gets the long value of the NodeId
     *
     * @return the long value of the NodeId
     */
    public long id() {
        return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(@NonNull final NodeId other) {
        Objects.requireNonNull(other, "NodeId cannot be null");
        return Long.compare(this.id, other.id);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String toString() {
        return Long.toString(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeLong(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        id = in.readLong();
    }

    /**
     * Deserialize a NodeId from a {@link SerializableDataInputStream}.
     *
     * @param in
     * 		the {@link SerializableDataInputStream} to read from
     * @return the deserialized NodeId
     * @throws IOException
     * 		thrown if an exception occurs while reading from the stream or the long value is negative,
     */
    public static NodeId deserializeLong(SerializableDataInputStream in, boolean allowNull) throws IOException {
        final long longValue = in.readLong();
        if (longValue < LOWEST_NODE_NUMBER) {
            if (allowNull) {
                return null;
            }
            throw new IOException("id must be non-negative");
        }
        return new NodeId(longValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || NodeId.class != o.getClass()) {
            return false;
        }
        final NodeId nodeId = (NodeId) o;
        return id == nodeId.id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
}
