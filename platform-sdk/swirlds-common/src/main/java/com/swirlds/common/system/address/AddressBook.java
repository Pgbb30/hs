/*
 * Copyright (C) 2016-2023 Hedera Hashgraph, LLC
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

package com.swirlds.common.system.address;

import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.system.NodeId;
import com.swirlds.common.system.address.internal.AddressBookIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * The Address of every known member of the swirld. The getters are public and the setters aren't, so it is read-only
 * for apps. When enableEventStreaming is set to be true, the memo field is required and should be unique.
 */
public class AddressBook extends PartialMerkleLeaf implements Iterable<Address>, MerkleLeaf {

    public static final long CLASS_ID = 0x4ee5498ef623fbe0L;

    private static class ClassVersion {
        /**
         * In this version, the version was written as a long.
         */
        public static final int ORIGINAL = 0;

        public static final int UNDOCUMENTED = 1;
        /**
         * In this version, ad-hoc code was used to read and write the list of addresses.
         */
        public static final int AD_HOC_SERIALIZATION = 2;
        /**
         * In this version, AddressBook uses the serialization utilities to read & write the list of addresses.
         */
        public static final int UTILITY_SERIALIZATION = 3;
        /**
         * In this version, the round number and next node ID fields were added to this class.
         */
        public static final int ADDRESS_BOOK_STORE_SUPPORT = 4;
        /**
         * In this version, NodeIds are SelfSerializable.
         */
        public static final int SELF_SERIALIZABLE_NODE_ID = 5;
    }

    // FUTURE WORK: remove this restriction and use other strategies to make serialization safe
    /**
     * The maximum number of addresses that are supported.
     */
    public static final int MAX_ADDRESSES = 1024;

    /**
     * The round number that should be used when the round number is unknown.
     */
    public static final long UNKNOWN_ROUND = Long.MIN_VALUE;

    /**
     * The round when this address book was created.
     */
    private long round = UNKNOWN_ROUND;

    /**
     * The next node ID that can be added must be greater than or equal to this value.
     */
    private NodeId nextNodeId = NodeId.FIRST_NODE_ID;

    /**
     * Maps node IDs to the address for that node ID.
     */
    private final Map<NodeId, Address> addresses = new HashMap<>();

    /**
     * A map of public keys to node ID.
     */
    private final Map<String /* public key */, NodeId> publicKeyToId = new HashMap<>();

    /**
     * A map of node IDs to indices within the address book. A node's index is equal to its position in a list of all
     * nodes sorted by node ID (from least to greatest).
     */
    private final Map<NodeId, Integer /* index */> nodeIndices = new HashMap<>();

    /**
     * All node IDs in this map, ordered least to greatest.
     */
    private final List<NodeId> orderedNodeIds = new ArrayList<>();

    /**
     * the total weight of all members
     */
    private long totalWeight;

    /**
     * the number of addresses with non-zero weight
     */
    private int numberWithWeight;

    /**
     * Create an empty address book.
     */
    public AddressBook() {
        this(new ArrayList<>());
    }

    /**
     * Copy constructor.
     */
    @SuppressWarnings("CopyConstructorMissesField")
    private AddressBook(@NonNull final AddressBook that) {
        super(that);
        Objects.requireNonNull(that, "AddressBook must not be null");

        for (final Address address : that) {
            this.addNewAddress(address);
        }

        this.round = that.round;
    }

    /**
     * Create an address book initialized with the given list of addresses.
     *
     * @param addresses the addresses to start with
     */
    public AddressBook(@NonNull final List<Address> addresses) {
        Objects.requireNonNull(addresses, "addresses must not be null");
        addresses.forEach(this::add);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.SELF_SERIALIZABLE_NODE_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * Get the round number when this address book was constructed.
     *
     * @return the round when this address book was constructed, or {@link #UNKNOWN_ROUND} if the round is unknown or
     * this address book was not constructed during a regular round
     */
    public long getRound() {
        return round;
    }

    /**
     * Set the round number when this address book was constructed.
     *
     * @param round the round when this address book was constructed, or {@link #UNKNOWN_ROUND} if the round is unknown
     *              or this address book was not constructed during a regular round
     * @return this object
     */
    public AddressBook setRound(final long round) {
        throwIfImmutable();
        this.round = round;
        return this;
    }

    /**
     * Get the number of addresses currently in the address book.
     *
     * @return the number of addresses
     */
    public int getSize() {
        return addresses.size();
    }

    /**
     * Check if this address book is empty.
     *
     * @return true if this address book contains no addresses
     */
    public boolean isEmpty() {
        return addresses.isEmpty();
    }

    /**
     * Get the number of addresses currently in the address book that have a weight greater than zero.
     *
     * @return the number of addresses with a weight greater than zero
     */
    public int getNumberWithWeight() {
        return numberWithWeight;
    }

    /**
     * Get the total weight of all members added together, where each member has nonnegative weight. This is zero if
     * there are no members.
     *
     * @return the total weight
     */
    public long getTotalWeight() {
        return totalWeight;
    }

    /**
     * Find the NodeId for the member whose address has the given public key. Returns null if it does not exist.
     *
     * @param publicKey the public key to look up
     * @return the NodeId of the member with that key, or null if it was not found
     */
    @Nullable
    public NodeId getNodeId(@NonNull final String publicKey) {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        return publicKeyToId.get(publicKey);
    }

    /**
     * Find the NodeId for the member at a given index within the address book.
     *
     * @param index the index within the address book
     * @return a NodeId
     */
    @NonNull
    public NodeId getNodeId(final int index) {
        if (index < 0 || index >= addresses.size()) {
            throw new NoSuchElementException("no address with index " + index + " exists");
        }

        return orderedNodeIds.get(index);
    }

    /**
     * Get the index within the address book of a given node ID.  Check that the addressbook {@link #contains(NodeId)}
     * the node ID to avoid throwing an exception.
     *
     * @param id the node's ID
     * @return the index of the node ID within the address book
     * @throws NoSuchElementException if the node ID does not exist in the address book.
     */
    public int getIndexOfNodeId(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "nodeId is null");
        if (!addresses.containsKey(id)) {
            throw new NoSuchElementException("no address with id " + id + " exists");
        }
        return nodeIndices.getOrDefault(id, -1);
    }

    /**
     * Get the next available node ID. When adding a new address, it must always have a node ID equal to this value.
     *
     * @return the next available node ID
     */
    @NonNull
    public NodeId getNextNodeId() {
        return nextNodeId;
    }

    /**
     * <p>
     * Set the expected next node ID to be added to this address book.
     * </p>
     *
     * <p>
     * WARNING: the next node ID is typically maintained internally by the address book, and incorrect configuration may
     * lead to undefined behavior. This value should only be manually set if an address book is being constructed from
     * scratch for a round later than genesis (as opposed to constructing the address book iteratively by replaying all
     * address book transactions since genesis).
     * </p>
     *
     * @param nextNodeId the next node ID for the address book
     * @return this object
     */
    @NonNull
    public AddressBook setNextNodeId(final long nextNodeId) {
        NodeId candidate = new NodeId(nextNodeId);
        for (final Address address : this) {
            if (address.getNodeId().compareTo(candidate) >= 0) {
                throw new IllegalArgumentException("This address book contains an address " + address
                        + " with a node ID that is greater or equal to " + nextNodeId);
            }
        }

        this.nextNodeId = candidate;
        return this;
    }

    /**
     * Get the address for the member with the given ID.  Use {@link #contains(NodeId)} to check for its existence and
     * avoid an exception.
     *
     * @param id the member ID of the address to get
     * @return the address
     * @throws NoSuchElementException if no address with the given ID exists
     */
    @NonNull
    public Address getAddress(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "NodeId is null");
        final Address address = addresses.get(id);
        if (address == null) {
            throw new NoSuchElementException("no address with id " + id + " exists");
        }
        return address;
    }

    /**
     * Check if an address for a given node ID is contained within this address book.
     *
     * @param id a node ID
     * @return true if this address book contains an address for the given node ID
     */
    public boolean contains(@Nullable final NodeId id) {
        return id != null && addresses.containsKey(id);
    }

    /**
     * The address book maintains a list of deterministically ordered node IDs. Add a new node ID to the end of that
     * list and record its index.
     *
     * @param nodeId the ID of the node being added
     */
    private void addToOrderedList(@NonNull final NodeId nodeId) {
        final int index = orderedNodeIds.size();
        orderedNodeIds.add(nodeId);
        nodeIndices.put(nodeId, index);
    }

    /**
     * The address book maintains a list of deterministically ordered node IDs. Remove a node ID from that list, remove
     * it from the index map, and update the indices of any node that had to be shifted as a result.
     *
     * @param nodeId the ID of the node being removed
     */
    private void removeNodeFromOrderedList(@NonNull final NodeId nodeId) {
        final int indexToRemove = nodeIndices.remove(nodeId);
        orderedNodeIds.remove(indexToRemove);

        for (int index = indexToRemove; index < orderedNodeIds.size(); index++) {
            nodeIndices.put(orderedNodeIds.get(index), index);
        }
    }

    /**
     * Updates the weight on the address with the given ID. If the address does not exist, a NoSuchElementException is
     * thrown. If the weight value is negative, an IllegalArgumentException is thrown.  If the address book is
     * immutable, a MutabilityException is thrown. This method does not validate the address book after updating the
     * address.  When the user is finished with making incremental changes, the final address book should be validated.
     *
     * @param id     the ID of the address to update.
     * @param weight the new weight value.  The weight must be nonnegative.
     * @throws NoSuchElementException   if the address does not exist.
     * @throws IllegalArgumentException if the weight is negative.
     * @throws MutabilityException      if the address book is immutable.
     */
    public void updateWeight(@NonNull final NodeId id, final long weight) {
        Objects.requireNonNull(id, "NodeId is null");
        throwIfImmutable();
        final Address address = getAddress(id);
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be nonnegative");
        }
        updateAddress(address.copySetWeight(weight));
    }

    /**
     * Update an existing entry in the address book.
     *
     * @param address the new address
     */
    private void updateAddress(@NonNull final Address address) {
        final Address oldAddress = Objects.requireNonNull(addresses.put(address.getNodeId(), address));

        publicKeyToId.remove(oldAddress.getNickname());
        publicKeyToId.put(address.getNickname(), address.getNodeId());

        final long oldWeight = oldAddress.getWeight();
        final long newWeight = address.getWeight();

        totalWeight -= oldWeight;
        totalWeight += newWeight;

        if (oldWeight == 0 && newWeight != 0) {
            numberWithWeight++;
        } else if (oldWeight != 0 && newWeight == 0) {
            numberWithWeight--;
        }

        addresses.put(address.getNodeId(), address);
    }

    /**
     * Add a new address.
     *
     * @param address the address to add
     */
    private void addNewAddress(@NonNull final Address address) {
        if (address.getNodeId().compareTo(nextNodeId) < 0) {
            throw new IllegalArgumentException("Can not add address for node with ID " + address.getNodeId()
                    + ", the next address to be added is required have a node ID greater or equal to "
                    + nextNodeId);
        }
        if (addresses.size() >= MAX_ADDRESSES) {
            throw new IllegalStateException("Address book is only permitted to hold " + MAX_ADDRESSES + " entries");
        }

        nextNodeId = new NodeId(address.getNodeId().id() + 1);

        addresses.put(address.getNodeId(), address);
        publicKeyToId.put(address.getNickname(), address.getNodeId());
        addToOrderedList(address.getNodeId());

        totalWeight += address.getWeight();
        if (!address.isZeroWeight()) {
            numberWithWeight++;
        }
    }

    /**
     * Add an address to the address book, replacing the existing address with the same ID if one is present.
     *
     * @param address the address for that member, may not be null, must have a node ID greater or equal to
     *                {@link #nextNodeId} if the address is not currently in the address book
     * @return this object
     * @throws IllegalStateException if a new address is added that has a node ID that is less than {@link #nextNodeId}
     */
    @NonNull
    public AddressBook add(@NonNull final Address address) {
        throwIfImmutable();
        Objects.requireNonNull(address, "address must not be null");

        if (addresses.containsKey(address.getNodeId())) {
            // FUTURE WORK: adding an address here is a strange API pattern
            updateAddress(address);
        } else {
            addNewAddress(address);
        }

        return this;
    }

    /**
     * Remove an address associated with a given node ID.
     *
     * @param id the node ID that should have its address removed
     * @return this object
     */
    @NonNull
    public AddressBook remove(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "NodeId is null");
        throwIfImmutable();

        final Address address = addresses.remove(id);

        if (address == null) {
            return this;
        }

        publicKeyToId.remove(address.getNickname());
        removeNodeFromOrderedList(id);

        totalWeight -= address.getWeight();
        if (!address.isZeroWeight()) {
            numberWithWeight--;
        }
        orderedNodeIds.remove(id);

        return this;
    }

    /**
     * Remove all addresses from the address book.
     */
    public void clear() {
        throwIfImmutable();

        addresses.clear();
        publicKeyToId.clear();
        nodeIndices.clear();
        orderedNodeIds.clear();

        totalWeight = 0;
        numberWithWeight = 0;
        nextNodeId = NodeId.FIRST_NODE_ID;
    }

    /**
     * Create a copy of this address book. The copy is always mutable, and the original maintains its original
     * mutability status.
     */
    @Override
    @NonNull
    public AddressBook copy() {
        return new AddressBook(this);
    }

    /**
     * Seal this address book, making it irreversibly immutable. Immutable address books may still be copied.
     *
     * @return this object
     */
    @NonNull
    public AddressBook seal() {
        setImmutable(true);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(@NonNull final SerializableDataOutputStream out) throws IOException {
        Objects.requireNonNull(out, "out must not be null");
        out.writeSerializableIterableWithSize(iterator(), addresses.size(), false, true);
        out.writeLong(round);
        out.writeSerializable(nextNodeId, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(@NonNull final SerializableDataInputStream in, final int version) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        in.readSerializableIterableWithSize(MAX_ADDRESSES, false, Address::new, this::addNewAddress);

        if (version < ClassVersion.ADDRESS_BOOK_STORE_SUPPORT) {
            round = UNKNOWN_ROUND;
            if (!orderedNodeIds.isEmpty()) {
                nextNodeId = new NodeId(orderedNodeIds.get(getSize() - 1).id() + 1);
            }
            return;
        }

        round = in.readLong();
        if (version < ClassVersion.SELF_SERIALIZABLE_NODE_ID) {
            nextNodeId = new NodeId(in.readLong());
        } else {
            nextNodeId = in.readSerializable(false, NodeId::new);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.UTILITY_SERIALIZATION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Iterator<Address> iterator() {
        return new AddressBookIterator(orderedNodeIds.iterator(), addresses);
    }

    /**
     * Get a set of all node IDs in the address book. Set is safe to modify.
     *
     * @return a set of all node IDs in the address book
     */
    @NonNull
    public Set<NodeId> getNodeIdSet() {
        return new HashSet<>(addresses.keySet());
    }

    /**
     * The text form of an address book that appears in config.txt
     *
     * @return the string form of the AddressBook that would appear in config.txt
     */
    @NonNull
    public String toConfigText() {
        return AddressBookUtils.addressBookConfigText(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final AddressBook that = (AddressBook) o;
        return Objects.equals(addresses, that.addresses)
                && getRound() == that.getRound()
                && Objects.equals(getNextNodeId(), that.getNextNodeId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return addresses.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("AddressBook {\n");
        for (final Address address : this) {
            sb.append("   ").append(address).append(",\n");
        }
        sb.append("}");

        return sb.toString();
    }
}
