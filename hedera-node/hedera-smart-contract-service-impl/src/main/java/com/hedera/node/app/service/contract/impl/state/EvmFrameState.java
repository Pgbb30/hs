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

package com.hedera.node.app.service.contract.impl.state;

import com.hedera.node.app.service.contract.impl.exec.operations.CustomCallOperation;
import com.hedera.node.app.spi.meta.bni.Scope;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.EvmAccount;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;

/**
 * Exposes the full Hedera state that may be read and changed <b>directly </b> from an EVM frame,
 * using data types appropriate to the Besu EVM API.
 *
 * <p>Of course, implementations must still reflect the state changes made by any calls to
 * the {@code 0x167} system contract from within the EVM frame. But those changes are, in a
 * sense, the result of "escaping" the EVM; so they are not part of this API.
 *
 * <p>Since almost all return values require translating from Hedera data types to Besu data
 * types, implementations might need to do internal caching to avoid excessive conversions.
 */
public interface EvmFrameState {
    /**
     * Constructs an {@link EvmFrameState} from the given {@link Scope}.
     *
     * @param scope the scope
     * @return an {@link EvmFrameState} that client code can use to manipulate scoped state via Besu data types
     */
    static EvmFrameState from(@NonNull final Scope scope) {
        return new DispatchingEvmFrameState(
                scope.dispatch(),
                scope.writableContractState().get(ContractSchema.STORAGE_KEY),
                scope.writableContractState().get(ContractSchema.BYTECODE_KEY));
    }

    /**
     * Collects the given fee from the given account. The caller should have already
     * verified that the account exists and has sufficient balance to pay the fee, so
     * this method surfaces any problem by throwing an exception.
     *
     * @param payer the account to collect the fee from
     * @param amount the amount to collect
     * @throws IllegalArgumentException if the collection fails for any reason
     */
    void collectFee(@NonNull Address payer, long amount);

    /**
     * Refunds the given {@code amount} of fees from the given {@code fromEntityNumber}.
     *
     * @param payer the address of the account to refund the fees to
     * @param amount          the amount of fees to collect
     */
    void refundFee(@NonNull Address payer, long amount);

    /**
     * Tries to transfer the given amount from a sending contract to the recipient. The sender
     * has already authorized this action, in the sense that it is the address that has initiated
     * either a message call with value or a {@code selfdestruct}. The recipient, however, must
     * still be checked for authorization based on the Hedera concept of receiver signature
     * requirements.
     *
     * <p>Returns true if the receiver authorization and transfer succeeded, false otherwise.
     *
     * @param sendingContract the sender of the transfer, already authorized
     * @param recipient       the recipient of the transfer, not yet authorized
     * @param amount          the amount to transfer
     * @param delegateCall    whether this transfer is done via code executed by a delegate call
     * @return a optional with the reason to halt if the transfer failed, or empty if it succeeded
     */
    Optional<ExceptionalHaltReason> tryTransferFromContract(
            @NonNull Address sendingContract, @NonNull Address recipient, long amount, boolean delegateCall);

    /**
     * Tries to initialize a "lazy-created" account at the given address. The standard creation pattern for a
     * Hedera account gives the account's key immediately. Lazy creation does not, instead initializing the
     * account with just the EVM address <i>derived from</i> an ECDSA public key.
     *
     * <p>Once we encounter a HAPI transaction with a full-prefix signature from this key, we can then finalize
     * the account by giving it the full key.
     *
     * <p>Lazy creation can fail for at least three reasons:
     * <ol>
     *   <li>There may be no more preceding child records available to externalize the creation.</li>
     *   <li>The Hedera accounts limit may be have been reached.</li>
     *   <li>There could already be an expired account at the given address.</li>
     * </ol>
     * Note the {@link CustomCallOperation} will have already confirmed that the Hedera EVM in use supports
     * lazy creation, and that it is enabled by properties.
     *
     * @param address the address of the account to try to lazy-create
     * @return an optional {@link ExceptionalHaltReason} with the reason lazy creation could not be done
     */
    Optional<ExceptionalHaltReason> tryLazyCreation(@NonNull Address address);

    /**
     * Returns whether the account with the given address is a "hollow account"; that is, an account
     * created by a value transfer to a 20-byte alias, without an explicit cryptographic key given.
     */
    boolean isHollowAccount(@NonNull Address address);

    /**
     * Given an address that is a "hollow account", finalizes the account as a contract.
     *
     * @param address the address of the hollow account to finalize
     */
    void finalizeHollowAccount(@NonNull Address address);

    /**
     * Attempts to track the given deletion of an account with the designated beneficiary, returning an optional
     * {@link ExceptionalHaltReason} to indicate whether the deletion could be successfully tracked.
     *
     * @param deleted the address of the account being deleted
     * @param beneficiary the address of the beneficiary of the deletion
     * @return an optional {@link ExceptionalHaltReason} with the reason deletion could not be tracked
     */
    Optional<ExceptionalHaltReason> tryTrackingDeletion(@NonNull Address deleted, @NonNull Address beneficiary);

    /**
     * Returns the read-only account with the given address, or {@code null} if the account is missing,
     * deleted, or expired; or if this get() used the account's "long zero" address and not is priority
     * EVM address.
     *
     * @param address the account address
     * @return the read-only account; or {@code null} if the account is missing, deleted, or expired
     */
    @Nullable
    Account getAccount(Address address);

    /**
     * Returns the mutable account with the given address, or {@code null} if the account is missing,
     * deleted, or expired; or if this get() used the account's "long zero" address and not is priority
     * EVM address.
     *
     * @param address the account address
     * @return the mutable account; or {@code null} if the account is missing, deleted, or expired
     */
    @Nullable
    EvmAccount getMutableAccount(Address address);

    @NonNull
    UInt256 getStorageValue(long number, @NonNull UInt256 key);

    void setStorageValue(long number, @NonNull UInt256 key, @NonNull UInt256 value);

    @NonNull
    UInt256 getOriginalStorageValue(long number, @NonNull UInt256 key);

    /**
     * Returns the code for the account with the given number, or empty code if no such code exists.
     *
     * @param number the account number
     * @return the code for the account
     */
    @NonNull
    Bytes getCode(long number);

    /**
     * Sets the code for the contract with the given number. Only used during contract creation.
     *
     * @param number the contract number
     * @param code the new code
     */
    void setCode(long number, @NonNull Bytes code);

    /**
     * Returns the redirect bytecode for the token with the given address, which must be a long-zero address.
     *
     * <p>Since a {@link TokenEvmAccount} never needs its Hedera entity number, we may as well use
     * the long-zero address there, and here.
     *
     * @param address the token long-zero address
     * @return the redirect code for the token
     */
    @NonNull
    Bytes getTokenRedirectCode(@NonNull Address address);

    @NonNull
    Hash getCodeHash(long number);

    /**
     * Returns the hash of the redirect bytecode for the token with the given address, which must be a
     * long-zero address.
     *
     * <p>Since a {@link TokenEvmAccount} never needs its Hedera entity number, we may as well use
     * the long-zero address there, and here.
     *
     * @param address the token long-zero address
     * @return the redirect code for the token
     */
    @NonNull
    Hash getTokenRedirectCodeHash(@NonNull Address address);

    /**
     * Returns the nonce for the account with the given number.
     *
     * @param number the account number
     * @return the nonce
     */
    long getNonce(long number);

    /**
     * Returns the number of treasury titles for the account with the given number.
     *
     * @param number the account number
     * @return the number of treasury titles
     */
    int getNumTreasuryTitles(long number);

    /**
     * Returns the number of positive token balances.
     *
     * @param number the account number
     * @return the number of positive token balances
     */
    int getNumPositiveTokenBalances(long number);

    /**
     * Returns whether the account with the given number is a contract.
     *
     * @param number the account number
     * @return whether the account is a contract
     */
    boolean isContract(long number);

    /**
     * Sets the nonce for the account with the given number.
     *
     * @param number the account number
     * @param nonce the new nonce
     */
    void setNonce(long number, long nonce);

    /**
     * Returns the balance of the account with the given number.
     *
     * @param number the account number
     * @return the balance
     */
    Wei getBalance(long number);

    /**
     * Returns the "priority" EVM address of the account with the given number, or null if the
     * account has been deleted.
     *
     * <p>The priority address is its 20-byte alias if applicable; or else the "long-zero" address
     * with the account number as the last 8 bytes of the zero address.
     *
     * @param number the account number
     * @return the priority EVM address of the account, or null if the account has been deleted
     * @throws IllegalArgumentException if the account does not exist
     */
    @Nullable
    Address getAddress(long number);

    /**
     * Returns the full list of account-scoped storage changes in the current scope.
     *
     * @return the full list of account-scoped storage changes
     */
    @NonNull
    List<StorageAccesses> getStorageChanges();

    /**
     * Returns the size of the underlying K/V state for contract storage.
     *
     * @return the size of the K/V state
     */
    long getKvStateSize();

    /**
     * Returns the rent factors for the account with the given number.
     *
     * @param number the account number
     * @return the rent factors
     */
    RentFactors getRentFactorsFor(long number);
}
