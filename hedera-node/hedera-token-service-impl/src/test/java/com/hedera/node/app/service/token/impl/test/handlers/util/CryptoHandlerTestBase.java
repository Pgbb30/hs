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

package com.hedera.node.app.service.token.impl.test.handlers.util;

import static com.hedera.node.app.service.mono.Utils.asHederaKey;
import static com.hedera.node.app.service.mono.pbj.PbjConverter.asBytes;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.test.util.SigReqAdapterUtils.UNSET_STAKED_ID;
import static com.hedera.test.utils.KeyUtils.A_COMPLEX_KEY;
import static com.hedera.test.utils.KeyUtils.B_COMPLEX_KEY;
import static com.hedera.test.utils.KeyUtils.C_COMPLEX_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.CryptoSignatureWaiversImpl;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.spi.fixtures.state.MapReadableKVState;
import com.hedera.node.app.spi.fixtures.state.MapWritableKVState;
import com.hedera.node.app.spi.key.HederaKey;
import com.hedera.node.app.spi.state.ReadableStates;
import com.hedera.node.app.spi.state.WritableStates;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.CommonUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
// FUTURE : Remove this and use CryptoTokenHandlerTestBase instead for all classes extending this class

@ExtendWith(MockitoExtension.class)
public class CryptoHandlerTestBase {
    public static final String ACCOUNTS = "ACCOUNTS";
    protected static final String ALIASES = "ALIASES";
    protected final Key key = A_COMPLEX_KEY;
    protected final Key otherKey = C_COMPLEX_KEY;
    protected final AccountID id = AccountID.newBuilder().accountNum(3).build();
    protected final Timestamp consensusTimestamp =
            Timestamp.newBuilder().seconds(1_234_567L).build();
    protected final Instant consensusInstant = Instant.ofEpochSecond(consensusTimestamp.seconds());
    protected final Key accountKey = A_COMPLEX_KEY;
    protected final HederaKey accountHederaKey = asHederaKey(accountKey).get();
    protected final Long accountNum = id.accountNum();

    private static final Key aPrimitiveKey = Key.newBuilder()
            .ed25519(Bytes.wrap("01234567890123456789012345678901"))
            .build();
    private static final Bytes edKeyAlias = Bytes.wrap(asBytes(Key.PROTOBUF, aPrimitiveKey));
    protected final AccountID alias = AccountID.newBuilder().alias(edKeyAlias).build();
    protected final byte[] evmAddress = CommonUtils.unhex("6aea3773ea468a814d954e6dec795bfee7d76e26");
    protected final ContractID contractAlias =
            ContractID.newBuilder().evmAddress(Bytes.wrap(evmAddress)).build();
    /*Contracts */
    protected final ContractID contract =
            ContractID.newBuilder().contractNum(1234).build();
    protected final AccountID deleteAccountId =
            AccountID.newBuilder().accountNum(3213).build();
    protected final AccountID transferAccountId =
            AccountID.newBuilder().accountNum(32134).build();
    protected final Long deleteAccountNum = deleteAccountId.accountNum();
    protected final Long transferAccountNum = transferAccountId.accountNum();

    protected final TokenID nft = TokenID.newBuilder().tokenNum(56789).build();
    protected final TokenID token = TokenID.newBuilder().tokenNum(6789).build();
    protected final AccountID spender = AccountID.newBuilder().accountNum(12345).build();
    protected final AccountID delegatingSpender =
            AccountID.newBuilder().accountNum(1234567).build();
    protected final AccountID owner = AccountID.newBuilder().accountNum(123456).build();
    protected final Key ownerKey = B_COMPLEX_KEY;
    protected final CryptoAllowance cryptoAllowance = CryptoAllowance.newBuilder()
            .spender(spender)
            .owner(owner)
            .amount(10L)
            .build();
    protected final TokenAllowance tokenAllowance = TokenAllowance.newBuilder()
            .spender(spender)
            .amount(10L)
            .tokenId(token)
            .owner(owner)
            .build();
    protected static final long defaultAutoRenewPeriod = 7200000L;
    protected static final long payerBalance = 10_000L;
    protected MapReadableKVState<Bytes, AccountID> readableAliases;

    protected MapReadableKVState<AccountID, Account> readableAccounts;
    protected MapWritableKVState<Bytes, AccountID> writableAliases;
    protected MapWritableKVState<AccountID, Account> writableAccounts;
    protected Account account;
    protected ReadableAccountStore readableStore;
    protected WritableAccountStore writableStore;

    protected Account deleteAccount;

    protected Account transferAccount;

    @Mock
    protected ReadableStates readableStates;

    @Mock(strictness = LENIENT)
    protected WritableStates writableStates;

    @Mock
    protected CryptoSignatureWaiversImpl waivers;

    @BeforeEach
    public void setUp() {
        account = givenValidAccount(accountNum);
        deleteAccount = givenValidAccount(deleteAccountNum)
                .copyBuilder()
                .accountNumber(deleteAccountNum)
                .key(accountKey)
                .numberPositiveBalances(0)
                .numberTreasuryTitles(0)
                .build();
        transferAccount = givenValidAccount(transferAccountNum)
                .copyBuilder()
                .accountNumber(transferAccountNum)
                .key(key)
                .build();
        refreshStoresWithCurrentTokenOnlyInReadable();
    }

    protected void basicMetaAssertions(final PreHandleContext context, final int keysSize) {
        assertThat(context.requiredNonPayerKeys()).hasSize(keysSize);
    }

    protected void resetStores() {
        readableAccounts = emptyReadableAccountStateBuilder().build();
        writableAccounts = emptyWritableAccountStateBuilder().build();
        readableAliases = emptyReadableAliasStateBuilder().build();
        writableAliases = emptyWritableAliasStateBuilder().build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        given(readableStates.<Bytes, AccountID>get(ALIASES)).willReturn(readableAliases);
        given(writableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(writableAccounts);
        given(writableStates.<Bytes, AccountID>get(ALIASES)).willReturn(writableAliases);
        readableStore = new ReadableAccountStoreImpl(readableStates);
        writableStore = new WritableAccountStore(writableStates);
    }

    protected void refreshStoresWithCurrentTokenOnlyInReadable() {
        readableAccounts = readableAccountState();
        writableAccounts = emptyWritableAccountStateBuilder().build();
        readableAliases = readableAliasState();
        writableAliases = emptyWritableAliasStateBuilder().build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        given(readableStates.<Bytes, AccountID>get(ALIASES)).willReturn(readableAliases);
        readableStore = new ReadableAccountStoreImpl(readableStates);
        writableStore = new WritableAccountStore(writableStates);
    }

    protected void refreshStoresWithCurrentTokenInWritable() {
        readableAccounts = readableAccountState();
        writableAccounts = writableAccountStateWithOneKey();
        readableAliases = readableAliasState();
        writableAliases = writableAliasesStateWithOneKey();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        given(readableStates.<Bytes, AccountID>get(ALIASES)).willReturn(readableAliases);
        given(writableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(writableAccounts);
        given(writableStates.<Bytes, AccountID>get(ALIASES)).willReturn(writableAliases);
        readableStore = new ReadableAccountStoreImpl(readableStates);
        writableStore = new WritableAccountStore(writableStates);
    }

    @NonNull
    protected MapWritableKVState<AccountID, Account> writableAccountStateWithOneKey() {
        return emptyWritableAccountStateBuilder()
                .value(id, account)
                .value(deleteAccountId, deleteAccount)
                .value(transferAccountId, transferAccount)
                .build();
    }

    @NonNull
    protected MapReadableKVState<AccountID, Account> readableAccountState() {
        return emptyReadableAccountStateBuilder()
                .value(id, account)
                .value(deleteAccountId, deleteAccount)
                .value(transferAccountId, transferAccount)
                .build();
    }

    @NonNull
    protected MapWritableKVState<Bytes, AccountID> writableAliasesStateWithOneKey() {
        return emptyWritableAliasStateBuilder()
                .value(alias.alias(), asAccount(accountNum))
                .value(contractAlias.evmAddress(), asAccount(contract.contractNum()))
                .build();
    }

    @NonNull
    protected MapReadableKVState<Bytes, AccountID> readableAliasState() {
        return emptyReadableAliasStateBuilder()
                .value(alias.alias(), asAccount(accountNum))
                .value(contractAlias.evmAddress(), asAccount(contract.contractNum()))
                .build();
    }

    @NonNull
    protected MapReadableKVState.Builder<AccountID, Account> emptyReadableAccountStateBuilder() {
        return MapReadableKVState.builder(ACCOUNTS);
    }

    @NonNull
    protected MapWritableKVState.Builder<AccountID, Account> emptyWritableAccountStateBuilder() {
        return MapWritableKVState.builder(ACCOUNTS);
    }

    @NonNull
    protected MapWritableKVState.Builder<Bytes, AccountID> emptyWritableAliasStateBuilder() {
        return MapWritableKVState.builder(ALIASES);
    }

    @NonNull
    protected MapReadableKVState.Builder<Bytes, AccountID> emptyReadableAliasStateBuilder() {
        return MapReadableKVState.builder(ALIASES);
    }

    protected Account givenValidAccount(final long accountNum) {
        return new Account(
                accountNum,
                alias.alias(),
                key,
                1_234_567L,
                payerBalance,
                "testAccount",
                false,
                1_234L,
                1_234_568L,
                UNSET_STAKED_ID,
                true,
                true,
                3,
                2,
                1,
                2,
                10,
                2,
                3,
                false,
                2,
                0,
                1000L,
                2,
                72000,
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                2,
                false,
                null);
    }

    protected void givenValidContract() {
        account = new Account(
                accountNum,
                alias.alias(),
                key,
                1_234_567L,
                payerBalance,
                "testAccount",
                false,
                1_234L,
                1_234_568L,
                UNSET_STAKED_ID,
                true,
                true,
                3,
                2,
                1,
                2,
                10,
                1,
                3,
                true,
                2,
                0,
                1000L,
                2,
                72000,
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                2,
                false,
                null);
    }

    protected AccountID accountID(final long num) {
        return AccountID.newBuilder().accountNum(num).build();
    }
}
