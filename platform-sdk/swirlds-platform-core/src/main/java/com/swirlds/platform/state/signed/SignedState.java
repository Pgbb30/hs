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

package com.swirlds.platform.state.signed;

import static com.swirlds.common.utility.Threshold.MAJORITY;
import static com.swirlds.logging.LogMarker.EXCEPTION;
import static com.swirlds.logging.LogMarker.SIGNED_STATE;
import static com.swirlds.platform.state.PlatformData.GENESIS_ROUND;
import static com.swirlds.platform.state.signed.SignedStateHistory.SignedStateAction.CREATION;
import static com.swirlds.platform.state.signed.SignedStateHistory.SignedStateAction.RELEASE;
import static com.swirlds.platform.state.signed.SignedStateHistory.SignedStateAction.RESERVE;

import com.swirlds.base.time.Time;
import com.swirlds.common.config.StateConfig;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.system.NodeId;
import com.swirlds.common.system.SwirldState;
import com.swirlds.common.system.address.Address;
import com.swirlds.common.system.address.AddressBook;
import com.swirlds.common.utility.ReferenceCounter;
import com.swirlds.common.utility.RuntimeObjectRecord;
import com.swirlds.common.utility.RuntimeObjectRegistry;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.state.MinGenInfo;
import com.swirlds.platform.state.State;
import com.swirlds.platform.state.signed.SignedStateHistory.SignedStateAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * <p>
 * This is a signed state, in a form that allows those outside the network to verify that it is a legitimate state at a
 * given time.
 * </p>
 *
 * <p>
 * It includes a copy of a SwirldsState at a given moment, and the address book, and the history of the address book,
 * and a set of signatures (with identities of the signers) attesting to it.
 * </p>
 *
 * <p>
 * It can be created at the moment all the famous witnesses become known for a given round. The signatures can be
 * created and collected for every round, or for every Nth round, or for each round whose last famous witness has a
 * consensus time stamp at least T seconds after the last signed state, or by some other criterion.
 * </p>
 *
 * <p>
 * The signed state is also saved to disk, and is given to a new member joining the network, or to an old member
 * rejoining after a long absence.
 * </p>
 */
public class SignedState implements SignedStateInfo {

    private static final Logger logger = LogManager.getLogger(SignedState.class);

    /**
     * the signatures collected so far (including from self)
     */
    private SigSet sigSet;

    /**
     * The total weight that has signed this state.
     */
    private long signingWeight;

    /**
     * Is this the last state saved before the freeze period
     */
    private boolean freezeState;

    /**
     * True if this state has been deleted. Used to prevent the same state from being deleted more than once.
     */
    private boolean deleted = false;

    /**
     * The root of the merkle state.
     */
    private final State state;

    /**
     * The timestamp of when this object was created.
     */
    private final Instant creationTimestamp = Instant.now();

    /**
     * If true, then this state should eventually be written to disk.
     */
    private boolean stateToSave;

    /**
     * Signed states are deleted on this background thread.
     */
    private SignedStateGarbageCollector signedStateGarbageCollector;

    /**
     * Used to track the lifespan of this signed state.
     */
    private final RuntimeObjectRecord registryRecord;

    /**
     * Information about how this signed state was used.
     */
    private final SignedStateHistory history;

    /**
     * Keeps track of reservations on this object.
     */
    private final ReferenceCounter reservations = new ReferenceCounter(this::destroy, this::onReferenceCountException);

    /**
     * Instantiate a signed state.
     *
     * @param platformContext the platform context
     * @param state           a fast copy of the state resulting from all transactions in consensus order from all
     *                        events with received rounds up through the round this SignedState represents
     * @param reason          a short description of why this SignedState is being created. Each location where a
     *                        SignedState is created should attempt to use a unique reason, as this makes debugging
     *                        reservation bugs easier.
     * @param freezeState     specifies whether this state is the last one saved before the freeze
     */
    public SignedState(
            @NonNull PlatformContext platformContext,
            @NonNull final State state,
            @NonNull String reason,
            final boolean freezeState) {
        this(platformContext, state, reason);
        this.freezeState = freezeState;
    }

    /**
     * Instantiate a signed state.
     *
     * @param platformContext the platform context
     * @param state           a fast copy of the state resulting from all transactions in consensus order from all
     *                        events with received rounds up through the round this SignedState represents
     * @param reason          a short description of why this SignedState is being created. Each location where a
     *                        SignedState is created should attempt to use a unique reason, as this makes debugging
     *                        reservation bugs easier.
     */
    public SignedState(@NonNull PlatformContext platformContext, @NonNull final State state, @NonNull String reason) {
        state.reserve();

        this.state = state;

        final StateConfig stateConfig = platformContext.getConfiguration().getConfigData(StateConfig.class);

        if (stateConfig.stateHistoryEnabled()) {
            history = new SignedStateHistory(Time.getCurrent(), getRound(), stateConfig.debugStackTracesEnabled());
            history.recordAction(CREATION, getReservationCount(), reason, null);
        } else {
            history = null;
        }

        registryRecord = RuntimeObjectRegistry.createRecord(getClass(), history);
        sigSet = new SigSet();
    }

    /**
     * Set a garbage collector, used to delete states on a background thread.
     */
    public synchronized void setGarbageCollector(
            @NonNull final SignedStateGarbageCollector signedStateGarbageCollector) {
        this.signedStateGarbageCollector = signedStateGarbageCollector;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getRound() {
        return state.getPlatformState().getPlatformData().getRound();
    }

    /**
     * Check if this state is the genesis state.
     *
     * @return true if this is the genesis state
     */
    public boolean isGenesisState() {
        return state.getPlatformState().getPlatformData().getRound() == GENESIS_ROUND;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull SigSet getSigSet() {
        return sigSet;
    }

    /**
     * Attach signatures to this state.
     *
     * @param sigSet the signatures to be attached to this signed state
     */
    public void setSigSet(@NonNull final SigSet sigSet) {
        this.sigSet = Objects.requireNonNull(sigSet);
        signingWeight = 0;
        final AddressBook addressBook = getAddressBook();
        for (final NodeId signingNode : sigSet) {
            if (addressBook.contains(signingNode)) {
                signingWeight += addressBook.getAddress(signingNode).getWeight();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull AddressBook getAddressBook() {
        return Objects.requireNonNull(
                getState().getPlatformState().getAddressBook(),
                "address book stored in this signed state is null, this should never happen");
    }

    /**
     * Get the root of the state. This object should not be held beyond the scope of this SignedState or else there is
     * risk that the state may be deleted unexpectedly.
     *
     * @return the state contained in the signed state
     */
    public @NonNull State getState() {
        return state;
    }

    /**
     * @return is this the last state saved before the freeze period
     */
    public boolean isFreezeState() {
        return freezeState;
    }

    /**
     * Reserves the SignedState for use. While reserved, this SignedState will not be deleted.
     *
     * @param reason a short description of why this SignedState is being reserved. Each location where a SignedState is
     *               reserved should attempt to use a unique reason, as this makes debugging reservation bugs easier.
     * @return a wrapper that holds the state and the reservation
     */
    public @NonNull ReservedSignedState reserve(@NonNull final String reason) {
        return new ReservedSignedState(this, reason);
    }

    /**
     * Increment reservation count.
     */
    void incrementReservationCount(@NonNull final String reason, final long reservationId) {
        if (history != null) {
            history.recordAction(RESERVE, getReservationCount(), reason, reservationId);
        }
        reservations.reserve();
    }

    /**
     * Decrement reservation count.
     */
    void decrementReservationCount(@NonNull final String reason, final long reservationId) {
        if (history != null) {
            history.recordAction(RELEASE, getReservationCount(), reason, reservationId);
        }
        reservations.release();
    }

    /**
     * Add this state to the queue to be deleted on a background thread.
     */
    private void destroy() {
        if (signedStateGarbageCollector == null
                || !signedStateGarbageCollector.executeOnGarbageCollectionThread(this::delete)) {
            logger.warn(
                    SIGNED_STATE.getMarker(),
                    "unable to enqueue state for deletion, " + "will delete state on calling thread {}",
                    Thread.currentThread().getName());
            synchronized (this) {
                delete();
            }
        }
    }

    /**
     * This method is called when there is a reference count exception.
     */
    private void onReferenceCountException() {
        if (history != null) {
            logger.error(
                    EXCEPTION.getMarker(), "SignedState reference count error detected, dumping history.\n{}", history);
        }
    }

    /**
     * <p>
     * Perform deletion on this signed state.
     * </p>
     *
     * <p>
     * Under normal operation, this method will only be called on the single-threaded background deletion handler.
     * However, if the queue fills up then a different thread may attempt to simultaneously call this method. Because of
     * that, this method must be synchronized.
     * </p>
     */
    private synchronized void delete() {
        final Instant start = Instant.now();

        if (reservations.isDestroyed()) {
            if (!deleted) {
                try {
                    deleted = true;

                    if (history != null) {
                        history.recordAction(SignedStateAction.DESTROY, getReservationCount(), null, null);
                    }
                    registryRecord.release();
                    state.release();

                    if (signedStateGarbageCollector != null) {
                        signedStateGarbageCollector.reportDeleteTime(Duration.between(start, Instant.now()));
                    }
                } catch (final Throwable ex) {
                    logger.error(EXCEPTION.getMarker(), "exception while attempting to delete signed state", ex);
                }
            }
        }
    }

    /**
     * Get the number of reservations.
     */
    public synchronized int getReservationCount() {
        return reservations.getReservationCount();
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

        final SignedState that = (SignedState) o;

        return new EqualsBuilder()
                .append(sigSet, that.sigSet)
                .append(state, that.state)
                .isEquals();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37).append(sigSet).append(state).toHashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "SS(round: %d, sigs: %d/%s, hash: %s)"
                .formatted(getRound(), signingWeight, getAddressBook().getTotalWeight(), state.getHash());
    }

    /**
     * Get the consensus timestamp for this signed state
     *
     * @return the consensus timestamp for this signed state.
     */
    public @NonNull Instant getConsensusTimestamp() {
        return state.getPlatformState().getPlatformData().getConsensusTimestamp();
    }

    /**
     * The wall clock time when this SignedState object was instantiated.
     */
    public @NonNull Instant getCreationTimestamp() {
        return creationTimestamp;
    }

    /**
     * Get the root node of the application's state
     *
     * @return the root node of the application's state.
     */
    public @NonNull SwirldState getSwirldState() {
        return state.getSwirldState();
    }

    /**
     * Get events in the platformState.
     *
     * @return events in the platformState
     */
    public @NonNull EventImpl[] getEvents() {
        return state.getPlatformState().getPlatformData().getEvents();
    }

    /**
     * Get the hash of the consensus events in this state.
     *
     * @return the hash of the consensus events in this state
     */
    public @NonNull Hash getHashEventsCons() {
        return state.getPlatformState().getPlatformData().getHashEventsCons();
    }

    /**
     * Get the number of consensus events in this state.
     *
     * @return the number of consensus events in this state
     */
    public long getNumEventsCons() {
        return state.getPlatformState().getPlatformData().getNumEventsCons();
    }

    /**
     * Get information about the minimum generation in this round.
     *
     * @return the minimum generation of famous witnesses per round
     */
    public @NonNull List<MinGenInfo> getMinGenInfo() {
        return state.getPlatformState().getPlatformData().getMinGenInfo();
    }

    /**
     * The minimum generation of famous witnesses for the round specified. This method only looks at non-ancient rounds
     * contained within this state.
     *
     * @param round the round whose minimum generation will be returned
     * @return the minimum generation for the round specified
     * @throws NoSuchElementException if the generation information for this round is not contained withing this state
     */
    public long getMinGen(final long round) {
        return getState().getPlatformState().getPlatformData().getMinGen(round);
    }

    /**
     * Return the round generation of the oldest round in this state
     *
     * @return the generation of the oldest round
     */
    public long getMinRoundGeneration() {
        return getState().getPlatformState().getPlatformData().getMinRoundGeneration();
    }

    /**
     * Get the timestamp of the last transaction added to this state.
     *
     * @return the timestamp of the last transaction added to this state
     */
    public @NonNull Instant getLastTransactionTimestamp() {
        return state.getPlatformState().getPlatformData().getLastTransactionTimestamp();
    }

    /**
     * Check if this is a state that needs to be eventually written to disk.
     *
     * @return true if this state eventually needs to be written to disk
     */
    public boolean isStateToSave() {
        return stateToSave;
    }

    /**
     * Set if this state eventually needs to be written to disk.
     *
     * @param stateToSave true if this state eventually needs to be written to disk
     */
    public void setStateToSave(final boolean stateToSave) {
        this.stateToSave = stateToSave;
    }

    /**
     * Get the total signing weight collected so far.
     *
     * @return total weight of members whose signatures have been collected
     */
    public long getSigningWeight() {
        return signingWeight;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isComplete() {
        return MAJORITY.isSatisfiedBy(signingWeight, getAddressBook().getTotalWeight());
    }

    /**
     * Throw an exception if this state has not been completely signed. This method does not validate signatures, call
     * {@link #pruneInvalidSignatures()} to guarantee that only valid signatures are considered.
     *
     * @throws SignedStateInvalidException if this state lacks sufficient signatures to be considered complete
     */
    public void throwIfIncomplete() {
        if (!isComplete()) {
            throw new SignedStateInvalidException(
                    "Signed state lacks sufficient valid signatures. This state has " + sigSet.size()
                            + " valid signatures representing " + signingWeight + "/"
                            + getAddressBook().getTotalWeight() + " weight");
        }
    }

    /**
     * Add a signature to the sigset if the signature is valid.
     *
     * @param nodeId    the ID of the signing node
     * @param signature the signature to add
     * @return true if the signed state is now complete as a result of the signature being added, false if the signed
     * state is either not complete or was previously complete prior to this signature
     */
    public boolean addSignature(@NonNull final NodeId nodeId, @NonNull final Signature signature) {
        return addSignature(getAddressBook(), nodeId, signature);
    }

    /**
     * Check if a signature is valid.  If a node has no weight, we consider the signature to be invalid.
     *
     * @param address   the address of the signer, or null if there is no signing address
     * @param signature the signature to check
     * @return true if the signature is valid, false otherwise
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isSignatureValid(@Nullable final Address address, @NonNull final Signature signature) {
        if (address == null) {
            // Signing node is not in the address book.
            return false;
        }

        if (address.getWeight() == 0) {
            // Signing node has no weight.
            return false;
        }

        return signature.verifySignature(state.getHash().getValue(), address.getSigPublicKey());
    }

    /**
     * Add a signature to the sigset if the signature is valid.
     *
     * @param addressBook use this address book to determine if the signature is valid or not
     * @param nodeId      the ID of the signing node
     * @param signature   the signature to add
     * @return true if the signed state is now complete as a result of the signature being added, false if the signed
     * state is either not complete or was previously complete prior to this signature
     */
    private boolean addSignature(
            @NonNull final AddressBook addressBook, @NonNull final NodeId nodeId, @NonNull final Signature signature) {
        Objects.requireNonNull(addressBook, "addressBook");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(signature, "signature");

        if (isComplete()) {
            // No need to add more signatures
            return false;
        }

        final Address address = addressBook.getAddress(nodeId);
        if (!isSignatureValid(address, signature)) {
            return false;
        }

        if (sigSet.hasSignature(address.getNodeId())) {
            // We already have this signature.
            return false;
        }

        sigSet.addSignature(nodeId, signature);
        signingWeight += address.getWeight();

        return isComplete();
    }

    /**
     * Remove all invalid signatures from a signed state. Uses the address book in the state when judging the validity
     * of signatures.
     */
    public void pruneInvalidSignatures() {
        pruneInvalidSignatures(getAddressBook());
    }

    /**
     * Remove all invalid signatures from a signed state.
     *
     * @param trustedAddressBook use this address book to determine signature validity instead of the one inside the
     *                           signed state. Useful if validating signed states from untrusted sources.
     */
    public void pruneInvalidSignatures(@NonNull final AddressBook trustedAddressBook) {
        Objects.requireNonNull(trustedAddressBook);

        final List<NodeId> signaturesToRemove = new ArrayList<>();
        for (final NodeId nodeId : sigSet) {
            final Address address = trustedAddressBook.contains(nodeId) ? trustedAddressBook.getAddress(nodeId) : null;
            if (!isSignatureValid(address, sigSet.getSignature(nodeId))) {
                signaturesToRemove.add(nodeId);
            }
        }

        for (final NodeId nodeId : signaturesToRemove) {
            sigSet.removeSignature(nodeId);
        }

        // Recalculate signing weight. We should do this even if we don't remove signatures.
        signingWeight = 0;
        for (final NodeId nodeId : sigSet) {
            if (trustedAddressBook.contains(nodeId)) {
                signingWeight += trustedAddressBook.getAddress(nodeId).getWeight();
            }
        }
    }

    /**
     * Get the reservation history for this object (if configured to gather history)
     *
     * @return the reservation history
     */
    @Nullable
    SignedStateHistory getHistory() {
        return history;
    }
}
