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

package com.hedera.node.app.state.merkle;

import com.google.protobuf.ByteString;
import com.hedera.node.app.service.mono.utils.EntityNum;
import com.hedera.node.app.spi.state.EmptyReadableStates;
import com.hedera.node.app.spi.state.EmptyWritableStates;
import com.hedera.node.app.spi.state.ReadableKVState;
import com.hedera.node.app.spi.state.ReadableQueueState;
import com.hedera.node.app.spi.state.ReadableSingletonState;
import com.hedera.node.app.spi.state.ReadableStates;
import com.hedera.node.app.spi.state.WritableKVState;
import com.hedera.node.app.spi.state.WritableKVStateBase;
import com.hedera.node.app.spi.state.WritableQueueState;
import com.hedera.node.app.spi.state.WritableQueueStateBase;
import com.hedera.node.app.spi.state.WritableSingletonState;
import com.hedera.node.app.spi.state.WritableSingletonStateBase;
import com.hedera.node.app.spi.state.WritableStates;
import com.hedera.node.app.state.HandleConsensusRoundListener;
import com.hedera.node.app.state.HederaState;
import com.hedera.node.app.state.PreHandleListener;
import com.hedera.node.app.state.merkle.disk.OnDiskReadableKVState;
import com.hedera.node.app.state.merkle.disk.OnDiskWritableKVState;
import com.hedera.node.app.state.merkle.memory.InMemoryReadableKVState;
import com.hedera.node.app.state.merkle.memory.InMemoryWritableKVState;
import com.hedera.node.app.state.merkle.queue.QueueNode;
import com.hedera.node.app.state.merkle.queue.ReadableQueueStateImpl;
import com.hedera.node.app.state.merkle.queue.WritableQueueStateImpl;
import com.hedera.node.app.state.merkle.singleton.ReadableSingletonStateImpl;
import com.hedera.node.app.state.merkle.singleton.SingletonNode;
import com.hedera.node.app.state.merkle.singleton.WritableSingletonStateImpl;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.common.system.InitTrigger;
import com.swirlds.common.system.Platform;
import com.swirlds.common.system.Round;
import com.swirlds.common.system.SoftwareVersion;
import com.swirlds.common.system.SwirldDualState;
import com.swirlds.common.system.SwirldState;
import com.swirlds.common.system.events.Event;
import com.swirlds.common.utility.Labeled;
import com.swirlds.fchashmap.FCHashMap;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An implementation of {@link SwirldState} and {@link HederaState}. The Hashgraph Platform
 * communicates with the application through {@link com.swirlds.common.system.SwirldMain} and {@link
 * SwirldState}. The Hedera application, after startup, only needs the ability to get {@link
 * ReadableStates} and {@link WritableStates} from this object.
 *
 * <p>Among {@link MerkleHederaState}'s child nodes are the various {@link
 * com.swirlds.merkle.map.MerkleMap}'s and {@link com.swirlds.virtualmap.VirtualMap}'s that make up
 * the service's states. Each such child node has a label specified that is computed from the
 * metadata for that state. Since both service names and state keys are restricted to characters
 * that do not include the period, we can use it to separate service name from state key. When we
 * need to find all states for a service, we can do so by iteration and string comparison.
 *
 * <p>NOTE: The implementation of this class must change before we can support state proofs
 * properly. In particular, a wide n-ary number of children is less than ideal, since the hash of
 * each child must be part of the state proof. It would be better to have a binary tree. We should
 * consider nesting service nodes in a MerkleMap, or some other such approach to get a binary tree.
 */
public class MerkleHederaState extends PartialNaryMerkleInternal implements MerkleInternal, SwirldState, HederaState {

    /** Used when asked for a service's readable states that we don't have */
    private static final ReadableStates EMPTY_READABLE_STATES = new EmptyReadableStates();
    /** Used when asked for a service's writable states that we don't have */
    private static final WritableStates EMPTY_WRITABLE_STATES = new EmptyWritableStates();

    // For serialization
    private static final long CLASS_ID = 0x2de3ead3caf06392L;
    private static final int VERSION_1 = 1;
    private static final int CURRENT_VERSION = VERSION_1;

    /**
     * This callback is invoked whenever the consensus round happens. The Hashgraph Platform, today,
     * only communicates the consensus round through the {@link SwirldState} interface. In the
     * future it will use a callback on a platform created via a platform builder. Until that
     * happens the only way our application will know of new transactions, will be through this
     * callback. Since this is not serialized and saved to state, it must be restored on application
     * startup. If this is never set, the application will never be able to handle a new round of
     * transactions.
     *
     * <p>This reference is moved forward to the working mutable state.
     */
    private HandleConsensusRoundListener onHandleConsensusRound;

    /**
     * This callback is invoked whenever there is an event to pre-handle.
     *
     * <p>This reference is moved forward to the working mutable state.
     */
    private final PreHandleListener onPreHandle;

    /**
     * This callback is invoked whenever the state is initialized.
     *
     * <p>This reference is moved forward to the working mutable state.
     */
    private OnStateInitialized onInit;

    /**
     * Maintains information about each service, and each state of each service, known by this
     * instance. The key is the "service-name.state-key".
     */
    private final Map<String, Map<String, StateMetadata<?, ?>>> services = new HashMap<>();

    /**
     * A rebuilt-map of all aliases.
     *
     * <p>NOTE: This field is TEMPORARY. Once we have eliminated mono-service, this alias map will
     * move to being a normal part of state using a VirtualMap, and we will no longer have it as a
     * separate in-memory data structure.</p>
     */
    private final FCHashMap<ByteString, EntityNum> aliases;

    /**
     * Create a new instance. This constructor must be used for all creations of this class.
     *
     * @param onPreHandle            The callback to invoke when an event is ready for pre-handle
     * @param onHandleConsensusRound The callback invoked when the platform has
     * @param onInit                 The callback to invoke when state is initialized by the platform
     */
    public MerkleHederaState(
            @NonNull final PreHandleListener onPreHandle,
            @NonNull final HandleConsensusRoundListener onHandleConsensusRound,
            @NonNull final OnStateInitialized onInit) {
        this.onPreHandle = Objects.requireNonNull(onPreHandle);
        this.onHandleConsensusRound = Objects.requireNonNull(onHandleConsensusRound);
        this.onInit = Objects.requireNonNull(onInit);
        this.aliases = new FCHashMap<>();
    }

    /**
     * This constructor ONLY exists for the benefit of the ConstructableRegistry. It is not actually
     * used except by the registry to create an instance of this class for the purpose of getting
     * the class ID. It should never be used for any other purpose. And one day we won't need it
     * anymore and can remove it.
     *
     * @deprecated This constructor is only for use by the ConstructableRegistry.
     */
    @Deprecated(forRemoval = true)
    public MerkleHederaState() {
        // ConstructableRegistry requires a "working" no-arg constructor
        aliases = null;
        onPreHandle = null;
    }

    /**
     * {@inheritDoc}
     *
     * Called by the platform whenever the state should be initialized. This can happen at genesis startup,
     * on restart, on reconnect, or any other time indicated by the {@code trigger}.
     */
    @Override
    public void init(
            final Platform platform,
            final SwirldDualState dualState,
            final InitTrigger trigger,
            final SoftwareVersion deserializedVersion) {
        // At some point this method will no longer be defined on SwirldState2, because we want to move
        // to a model where SwirldState/SwirldState2 are simply data objects, without this lifecycle.
        // Instead, this method will be a callback the app registers with the platform. So for now,
        // we simply call the callback handler, which is implemented by the app.
        this.onInit.onStateInitialized(this, platform, dualState, trigger, deserializedVersion);
    }

    /**
     * Private constructor for fast-copy.
     *
     * @param from The other state to fast-copy from. Cannot be null.
     */
    private MerkleHederaState(@NonNull final MerkleHederaState from) {
        // Copy the Merkle route from the source instance
        super(from);

        // Make a copy of the aliases map
        this.aliases = from.aliases.copy();

        // Copy over the metadata
        for (final var entry : from.services.entrySet()) {
            this.services.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }

        // Copy the non-null Merkle children from the source (should also be handled by super, TBH).
        // Note we don't "compress" -- null children remain in here unless we manually remove them
        // (which would cause massive re-hashing).
        for (int childIndex = 0, n = from.getNumberOfChildren(); childIndex < n; childIndex++) {
            final var childToCopy = from.getChild(childIndex);
            if (childToCopy != null) {
                setChild(childIndex, childToCopy.copy());
            }
        }

        // **MOVE** over the handle listener. Don't leave it on the immutable state
        this.onHandleConsensusRound = from.onHandleConsensusRound;
        from.onHandleConsensusRound = null;

        // **MOVE** over the pre-handle; but also leave on the immutable state
        this.onPreHandle = from.onPreHandle;

        // **MOVE** over the onInit handler. Don't leave it on the immutable state
        this.onInit = from.onInit;
        from.onInit = null;
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return CURRENT_VERSION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ReadableStates createReadableStates(@NonNull final String serviceName) {
        final var stateMetadata = services.get(serviceName);
        return stateMetadata == null ? EMPTY_READABLE_STATES : new MerkleReadableStates(stateMetadata);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public WritableStates createWritableStates(@NonNull final String serviceName) {
        throwIfImmutable();
        final var stateMetadata = services.get(serviceName);
        return stateMetadata == null ? EMPTY_WRITABLE_STATES : new MerkleWritableStates(stateMetadata);
    }

    /** {@inheritDoc} */
    @Override
    public MerkleHederaState copy() {
        throwIfImmutable();
        throwIfDestroyed();
        setImmutable(true);
        return new MerkleHederaState(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleConsensusRound(@NonNull final Round round, @NonNull final SwirldDualState swirldDualState) {
        throwIfImmutable();
        if (onHandleConsensusRound != null) {
            onHandleConsensusRound.onConsensusRound(round, swirldDualState, this);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void preHandle(@NonNull final Event event) {
        if (onPreHandle != null) {
            onPreHandle.onPreHandle(event, this);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNode migrate(final int ignored) {
        // Always return this node, we never want to replace MerkleHederaState node in the tree
        return this;
    }

    <K, V> void putServiceStateIfAbsent(@NonNull final StateMetadata<K, V> md) {
        throwIfImmutable();
        Objects.requireNonNull(md);
        final var stateMetadata = services.computeIfAbsent(md.serviceName(), k -> new HashMap<>());
        stateMetadata.put(md.stateDefinition().stateKey(), md);
    }

    /**
     * Puts the defined service state and its associated node into the merkle tree. The precondition
     * for calling this method is that node MUST be a {@link MerkleMap} or {@link VirtualMap} and
     * MUST have a correct label applied.
     *
     * @param md   The metadata associated with the state
     * @param node The node to add. Cannot be null.
     * @throws IllegalArgumentException if the node is neither a merkle map nor virtual map, or if
     *                                  it doesn't have a label, or if the label isn't right.
     */
    <K, V> void putServiceStateIfAbsent(@NonNull final StateMetadata<K, V> md, @NonNull final MerkleNode node) {

        // Validate the inputs
        throwIfImmutable();
        Objects.requireNonNull(md);
        Objects.requireNonNull(node);

        final var label = node instanceof Labeled labeled ? labeled.getLabel() : null;
        if (label == null) {
            throw new IllegalArgumentException("`node` must be a Labeled and have a label");
        }

        final var def = md.stateDefinition();
        if (def.onDisk() && !(node instanceof VirtualMap<?, ?>)) {
            throw new IllegalArgumentException(
                    "Mismatch: state definition claims on-disk, but " + "the merkle node is not a VirtualMap");
        }

        if (label.isEmpty()) {
            // It looks like both MerkleMap and VirtualMap do not allow for a null label.
            // But I want to leave this check in here anyway, in case that is ever changed.
            throw new IllegalArgumentException("A label must be specified on the node");
        }

        if (!label.equals(StateUtils.computeLabel(md.serviceName(), def.stateKey()))) {
            throw new IllegalArgumentException(
                    "A label must be computed based on the same " + "service name and state key in the metadata!");
        }

        // Put this metadata into the map
        final var stateMetadata = services.computeIfAbsent(md.serviceName(), k -> new HashMap<>());
        stateMetadata.put(def.stateKey(), md);

        // Look for a node, and if we don't find it, then insert the one we were given
        // If there is not a node there, then set it. I don't want to overwrite the existing node,
        // because it may have been loaded from state on disk, and the node provided here in this
        // call is always for genesis. So we may just ignore it.
        if (findNodeIndex(md.serviceName(), def.stateKey()) == -1) {
            setChild(getNumberOfChildren(), node);
        }
    }

    /**
     * Removes the node and metadata from the state merkle tree.
     *
     * @param serviceName The service name. Cannot be null.
     * @param stateKey    The state key
     */
    void removeServiceState(@NonNull final String serviceName, @NonNull final String stateKey) {
        throwIfImmutable();
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(stateKey);

        // Remove the metadata entry
        final var stateMetadata = services.get(serviceName);
        if (stateMetadata != null) {
            stateMetadata.remove(stateKey);
        }

        // Remove the node
        final var index = findNodeIndex(serviceName, stateKey);
        if (index != -1) {
            setChild(index, null);
        }
    }

    /**
     * Simple utility method that finds the state node index.
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @return -1 if not found, otherwise the index into the children
     */
    private int findNodeIndex(@NonNull final String serviceName, @NonNull final String stateKey) {
        final var label = StateUtils.computeLabel(serviceName, stateKey);
        for (int i = 0, n = getNumberOfChildren(); i < n; i++) {
            final var node = getChild(i);
            if (node instanceof Labeled labeled && label.equals(labeled.getLabel())) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Base class implementation for states based on MerkleTree
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private abstract class MerkleStates implements ReadableStates {
        private final Map<String, StateMetadata<?, ?>> stateMetadata;
        protected final Map<String, ReadableKVState<?, ?>> kvInstances;
        protected final Map<String, ReadableSingletonState<?>> singletonInstances;
        protected final Map<String, ReadableQueueState<?>> queueInstances;
        private final Set<String> stateKeys;

        /**
         * Create a new instance
         *
         * @param stateMetadata cannot be null
         */
        MerkleStates(@NonNull final Map<String, StateMetadata<?, ?>> stateMetadata) {
            this.stateMetadata = Objects.requireNonNull(stateMetadata);
            this.stateKeys = Collections.unmodifiableSet(stateMetadata.keySet());
            this.kvInstances = new HashMap<>();
            this.singletonInstances = new HashMap<>();
            this.queueInstances = new HashMap<>();
        }

        @NonNull
        @Override
        public <K, V> ReadableKVState<K, V> get(@NonNull String stateKey) {
            final ReadableKVState<K, V> instance = (ReadableKVState<K, V>) kvInstances.get(stateKey);
            if (instance != null) {
                return instance;
            }

            final var md = stateMetadata.get(stateKey);
            if (md == null || md.stateDefinition().singleton()) {
                throw new IllegalArgumentException("Unknown k/v state key '" + stateKey + ";");
            }

            final var node = findNode(md);
            if (node instanceof VirtualMap v) {
                final var ret = createReadableKVState(md, v);
                kvInstances.put(stateKey, ret);
                return ret;
            } else if (node instanceof MerkleMap m) {
                final var ret = createReadableKVState(md, m);
                kvInstances.put(stateKey, ret);
                return ret;
            } else {
                // This exception should never be thrown. Only if "findNode" found the wrong node!
                throw new IllegalStateException("Unexpected type for k/v state " + stateKey);
            }
        }

        @NonNull
        @Override
        public <T> ReadableSingletonState<T> getSingleton(@NonNull String stateKey) {
            final ReadableSingletonState<T> instance = (ReadableSingletonState<T>) singletonInstances.get(stateKey);
            if (instance != null) {
                return instance;
            }

            final var md = stateMetadata.get(stateKey);
            if (md == null || !md.stateDefinition().singleton()) {
                throw new IllegalArgumentException("Unknown singleton state key '" + stateKey + "'");
            }

            final var node = findNode(md);
            if (node instanceof SingletonNode s) {
                final var ret = createReadableSingletonState(md, s);
                singletonInstances.put(stateKey, ret);
                return ret;
            } else {
                // This exception should never be thrown. Only if "findNode" found the wrong node!
                throw new IllegalStateException("Unexpected type for singleton state " + stateKey);
            }
        }

        @NonNull
        @Override
        public <E> ReadableQueueState<E> getQueue(@NonNull String stateKey) {
            final ReadableQueueState<E> instance = (ReadableQueueState<E>) queueInstances.get(stateKey);
            if (instance != null) {
                return instance;
            }

            final var md = stateMetadata.get(stateKey);
            if (md == null || !md.stateDefinition().queue()) {
                throw new IllegalArgumentException("Unknown queue state key '" + stateKey + "'");
            }

            final var node = findNode(md);
            if (node instanceof QueueNode q) {
                final var ret = createReadableQueueState(md, q);
                queueInstances.put(stateKey, ret);
                return ret;
            } else {
                // This exception should never be thrown. Only if "findNode" found the wrong node!
                throw new IllegalStateException("Unexpected type for queue state " + stateKey);
            }
        }

        @Override
        public boolean contains(@NonNull final String stateKey) {
            return stateMetadata.containsKey(stateKey);
        }

        @NonNull
        @Override
        public Set<String> stateKeys() {
            return stateKeys;
        }

        @NonNull
        protected abstract ReadableKVState createReadableKVState(@NonNull StateMetadata md, @NonNull VirtualMap v);

        @NonNull
        protected abstract ReadableKVState createReadableKVState(@NonNull StateMetadata md, @NonNull MerkleMap m);

        @NonNull
        protected abstract ReadableSingletonState createReadableSingletonState(
                @NonNull StateMetadata md, @NonNull SingletonNode<?> s);

        @NonNull
        protected abstract ReadableQueueState createReadableQueueState(
                @NonNull StateMetadata md, @NonNull QueueNode<?> q);

        /**
         * Utility method for finding and returning the given node. Will throw an ISE if such a node
         * cannot be found!
         *
         * @param md The metadata
         * @return The found node
         */
        @NonNull
        private MerkleNode findNode(@NonNull final StateMetadata<?, ?> md) {
            final var index =
                    findNodeIndex(md.serviceName(), md.stateDefinition().stateKey());
            if (index == -1) {
                // This can only happen if there WAS a node here, and it was removed!
                throw new IllegalStateException("State '"
                        + md.stateDefinition().stateKey()
                        + "' for service '"
                        + md.serviceName()
                        + "' is missing from the merkle tree!");
            }

            return getChild(index);
        }
    }

    /**
     * An implementation of {@link ReadableStates} based on the merkle tree.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public final class MerkleReadableStates extends MerkleStates {
        /**
         * Create a new instance
         *
         * @param stateMetadata cannot be null
         */
        MerkleReadableStates(@NonNull final Map<String, StateMetadata<?, ?>> stateMetadata) {
            super(stateMetadata);
        }

        @Override
        @NonNull
        protected ReadableKVState<?, ?> createReadableKVState(
                @NonNull final StateMetadata md, @NonNull final VirtualMap v) {
            return new OnDiskReadableKVState<>(md, v);
        }

        @Override
        @NonNull
        protected ReadableKVState<?, ?> createReadableKVState(
                @NonNull final StateMetadata md, @NonNull final MerkleMap m) {
            return new InMemoryReadableKVState<>(md, m);
        }

        @Override
        @NonNull
        protected ReadableSingletonState<?> createReadableSingletonState(
                @NonNull final StateMetadata md, @NonNull final SingletonNode<?> s) {
            return new ReadableSingletonStateImpl<>(md, s);
        }

        @NonNull
        @Override
        protected ReadableQueueState createReadableQueueState(@NonNull StateMetadata md, @NonNull QueueNode<?> q) {
            return new ReadableQueueStateImpl(md, q);
        }
    }

    /**
     * An implementation of {@link WritableStates} based on the merkle tree.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public final class MerkleWritableStates extends MerkleStates implements WritableStates {
        /**
         * Create a new instance
         *
         * @param stateMetadata cannot be null
         */
        MerkleWritableStates(@NonNull final Map<String, StateMetadata<?, ?>> stateMetadata) {
            super(stateMetadata);
        }

        @NonNull
        @Override
        public <K, V> WritableKVState<K, V> get(@NonNull String stateKey) {
            return (WritableKVState<K, V>) super.get(stateKey);
        }

        @NonNull
        @Override
        public <T> WritableSingletonState<T> getSingleton(@NonNull String stateKey) {
            return (WritableSingletonState<T>) super.getSingleton(stateKey);
        }

        @NonNull
        @Override
        public <E> WritableQueueState<E> getQueue(@NonNull String stateKey) {
            return (WritableQueueState<E>) super.getQueue(stateKey);
        }

        @Override
        @NonNull
        protected WritableKVState<?, ?> createReadableKVState(
                @NonNull final StateMetadata md, @NonNull final VirtualMap v) {
            return new OnDiskWritableKVState<>(md, v);
        }

        @Override
        @NonNull
        protected WritableKVState<?, ?> createReadableKVState(
                @NonNull final StateMetadata md, @NonNull final MerkleMap m) {
            return new InMemoryWritableKVState<>(md, m);
        }

        @Override
        @NonNull
        protected WritableSingletonState<?> createReadableSingletonState(
                @NonNull final StateMetadata md, @NonNull final SingletonNode<?> s) {
            return new WritableSingletonStateImpl<>(md, s);
        }

        @NonNull
        @Override
        protected WritableQueueState<?> createReadableQueueState(
                @NonNull final StateMetadata md, @NonNull final QueueNode<?> q) {
            return new WritableQueueStateImpl<>(md, q);
        }

        public void commit() {
            for (final ReadableKVState kv : kvInstances.values()) {
                ((WritableKVStateBase) kv).commit();
            }
            for (final ReadableSingletonState s : singletonInstances.values()) {
                ((WritableSingletonStateBase) s).commit();
            }
            for (final ReadableQueueState s : queueInstances.values()) {
                ((WritableQueueStateBase) s).commit();
            }
        }
    }
}
