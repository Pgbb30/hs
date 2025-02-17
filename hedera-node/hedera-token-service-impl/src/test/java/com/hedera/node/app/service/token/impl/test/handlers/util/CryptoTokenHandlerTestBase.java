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

import static com.hedera.node.app.service.mono.pbj.PbjConverter.asBytes;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.test.util.SigReqAdapterUtils.UNSET_STAKED_ID;
import static com.hedera.test.utils.KeyUtils.A_COMPLEX_KEY;
import static com.hedera.test.utils.KeyUtils.B_COMPLEX_KEY;
import static com.hedera.test.utils.KeyUtils.C_COMPLEX_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenSupplyType;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountApprovalForAllAllowance;
import com.hedera.hapi.node.state.token.AccountCryptoAllowance;
import com.hedera.hapi.node.state.token.AccountFungibleTokenAllowance;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.FractionalFee;
import com.hedera.hapi.node.transaction.RoyaltyFee;
import com.hedera.node.app.config.VersionedConfigImpl;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableNftStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenRelationStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.spi.fixtures.state.MapReadableKVState;
import com.hedera.node.app.spi.fixtures.state.MapWritableKVState;
import com.hedera.node.app.spi.state.ReadableStates;
import com.hedera.node.app.spi.state.WritableStates;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.CommonUtils;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class CryptoTokenHandlerTestBase extends StateBuilderUtil {
    /* ---------- Keys */
    protected final Key key = A_COMPLEX_KEY;
    protected static final Key payerKey = A_COMPLEX_KEY;
    protected final Key ownerKey = B_COMPLEX_KEY;
    protected final Key spenderKey = C_COMPLEX_KEY;
    protected final Key adminKey = A_COMPLEX_KEY;
    protected final Key pauseKey = B_COMPLEX_KEY;
    protected final Key wipeKey = C_COMPLEX_KEY;
    protected final Key kycKey = A_COMPLEX_KEY;
    protected final Key feeScheduleKey = A_COMPLEX_KEY;
    protected final Key supplyKey = A_COMPLEX_KEY;
    protected final Key freezeKey = A_COMPLEX_KEY;
    protected final Key treasuryKey = C_COMPLEX_KEY;

    /* ---------- Account IDs */
    protected final AccountID payerId = AccountID.newBuilder().accountNum(3).build();
    protected final AccountID deleteAccountId =
            AccountID.newBuilder().accountNum(3213).build();
    protected final AccountID transferAccountId =
            AccountID.newBuilder().accountNum(32134).build();
    protected final AccountID delegatingSpenderId =
            AccountID.newBuilder().accountNum(1234567).build();
    protected final AccountID ownerId =
            AccountID.newBuilder().accountNum(123456).build();
    protected final AccountID treasuryId =
            AccountID.newBuilder().accountNum(1000000).build();
    protected final AccountID autoRenewId = AccountID.newBuilder().accountNum(4).build();
    protected final AccountID spenderId =
            AccountID.newBuilder().accountNum(12345).build();
    protected final AccountID feeCollectorId = transferAccountId;

    /* ---------- Account Numbers ---------- */
    protected final Long accountNum = payerId.accountNum();

    /* ---------- Aliases ----------  */
    private static final Key aPrimitiveKey = Key.newBuilder()
            .ed25519(Bytes.wrap("01234567890123456789012345678901"))
            .build();
    private static final Bytes edKeyAlias = Bytes.wrap(asBytes(Key.PROTOBUF, aPrimitiveKey));
    protected final AccountID alias = AccountID.newBuilder().alias(edKeyAlias).build();
    protected final byte[] evmAddress = CommonUtils.unhex("6aea3773ea468a814d954e6dec795bfee7d76e25");
    protected final ContractID contractAlias =
            ContractID.newBuilder().evmAddress(Bytes.wrap(evmAddress)).build();
    /*Contracts */
    protected final ContractID contract =
            ContractID.newBuilder().contractNum(1234).build();
    /* ---------- Tokens ---------- */
    protected final TokenID fungibleTokenId = asToken(1L);

    protected final TokenID nonFungibleTokenId = asToken(2L);
    protected final TokenID fungibleTokenIDB = asToken(6L);
    protected final TokenID fungibleTokenIDC = asToken(7L);

    protected final EntityIDPair fungiblePair = EntityIDPair.newBuilder()
            .accountId(payerId)
            .tokenId(fungibleTokenId)
            .build();
    protected final EntityIDPair nonFungiblePair = EntityIDPair.newBuilder()
            .accountId(payerId)
            .tokenId(nonFungibleTokenId)
            .build();
    protected final EntityIDPair ownerFTPair = EntityIDPair.newBuilder()
            .accountId(ownerId)
            .tokenId(fungibleTokenId)
            .build();
    protected final EntityIDPair ownerNFTPair = EntityIDPair.newBuilder()
            .accountId(ownerId)
            .tokenId(nonFungibleTokenId)
            .build();

    protected final EntityIDPair feeCollectorFTPair = EntityIDPair.newBuilder()
            .accountId(feeCollectorId)
            .tokenId(fungibleTokenId)
            .build();
    protected final EntityIDPair feeCollectorNFTPair = EntityIDPair.newBuilder()
            .accountId(feeCollectorId)
            .tokenId(nonFungibleTokenId)
            .build();

    protected final EntityIDPair treasuryFTPair = EntityIDPair.newBuilder()
            .accountId(treasuryId)
            .tokenId(fungibleTokenId)
            .build();
    protected final EntityIDPair treasuryNFTPair = EntityIDPair.newBuilder()
            .accountId(treasuryId)
            .tokenId(nonFungibleTokenId)
            .build();
    protected final EntityIDPair ownerFTBPair = EntityIDPair.newBuilder()
            .accountId(ownerId)
            .tokenId(fungibleTokenIDB)
            .build();
    protected final EntityIDPair ownerFTCPair = EntityIDPair.newBuilder()
            .accountId(ownerId)
            .tokenId(fungibleTokenIDC)
            .build();
    protected final EntityIDPair feeCollectorFTBPair = EntityIDPair.newBuilder()
            .accountId(feeCollectorId)
            .tokenId(fungibleTokenIDB)
            .build();
    protected final EntityIDPair feeCollectorFTCPair = EntityIDPair.newBuilder()
            .accountId(feeCollectorId)
            .tokenId(fungibleTokenIDC)
            .build();
    protected final NftID nftIdSl1 =
            NftID.newBuilder().tokenId(nonFungibleTokenId).serialNumber(1L).build();
    protected final NftID nftIdSl2 =
            NftID.newBuilder().tokenId(nonFungibleTokenId).serialNumber(2L).build();

    /* ---------- Allowances --------------- */
    protected final CryptoAllowance cryptoAllowance = CryptoAllowance.newBuilder()
            .spender(spenderId)
            .owner(ownerId)
            .amount(10L)
            .build();
    protected final TokenAllowance tokenAllowance = TokenAllowance.newBuilder()
            .spender(spenderId)
            .amount(10L)
            .tokenId(fungibleTokenId)
            .owner(ownerId)
            .build();
    protected final NftAllowance nftAllowance = NftAllowance.newBuilder()
            .spender(spenderId)
            .owner(ownerId)
            .tokenId(nonFungibleTokenId)
            .serialNumbers(List.of(1L, 2L))
            .build();
    protected final NftAllowance nftAllowanceWithApproveForALl =
            nftAllowance.copyBuilder().approvedForAll(Boolean.TRUE).build();
    protected final NftAllowance nftAllowanceWithDelegatingSpender = NftAllowance.newBuilder()
            .spender(spenderId)
            .owner(ownerId)
            .tokenId(nonFungibleTokenId)
            .approvedForAll(Boolean.FALSE)
            .serialNumbers(List.of(1L, 2L))
            .delegatingSpender(delegatingSpenderId)
            .build();
    /* ---------- Fees ------------------ */
    protected FixedFee hbarFixedFee = FixedFee.newBuilder().amount(1_000L).build();
    protected FixedFee htsFixedFee = FixedFee.newBuilder()
            .amount(10L)
            .denominatingTokenId(fungibleTokenId)
            .build();
    protected FractionalFee fractionalFee = FractionalFee.newBuilder()
            .maximumAmount(100L)
            .minimumAmount(1L)
            .fractionalAmount(
                    Fraction.newBuilder().numerator(1).denominator(100).build())
            .netOfTransfers(false)
            .build();
    protected RoyaltyFee royaltyFee = RoyaltyFee.newBuilder()
            .exchangeValueFraction(
                    Fraction.newBuilder().numerator(1).denominator(2).build())
            .fallbackFee(hbarFixedFee)
            .build();
    protected List<CustomFee> customFees = List.of(withFixedFee(hbarFixedFee), withFractionalFee(fractionalFee));

    /* ---------- Misc ---------- */
    protected final Timestamp consensusTimestamp =
            Timestamp.newBuilder().seconds(1_234_567L).build();
    protected final Instant consensusInstant = Instant.ofEpochSecond(1_234_567L);
    protected final String tokenName = "test token";
    protected final String tokenSymbol = "TT";
    protected final String memo = "test memo";
    protected final long expirationTime = 1_234_567L;
    protected final long autoRenewSecs = 100L;
    protected static final long payerBalance = 10_000L;
    /* ---------- States ---------- */
    protected MapReadableKVState<Bytes, AccountID> readableAliases;
    protected MapReadableKVState<AccountID, Account> readableAccounts;
    protected MapWritableKVState<Bytes, AccountID> writableAliases;
    protected MapWritableKVState<AccountID, Account> writableAccounts;
    protected MapReadableKVState<TokenID, Token> readableTokenState;
    protected MapWritableKVState<TokenID, Token> writableTokenState;
    protected MapReadableKVState<EntityIDPair, TokenRelation> readableTokenRelState;
    protected MapWritableKVState<EntityIDPair, TokenRelation> writableTokenRelState;
    protected MapReadableKVState<NftID, Nft> readableNftState;
    protected MapWritableKVState<NftID, Nft> writableNftState;

    /* ---------- Stores */

    protected ReadableTokenStore readableTokenStore;
    protected WritableTokenStore writableTokenStore;

    protected ReadableAccountStore readableAccountStore;
    protected WritableAccountStore writableAccountStore;
    protected ReadableTokenRelationStore readableTokenRelStore;
    protected WritableTokenRelationStore writableTokenRelStore;
    protected ReadableNftStore readableNftStore;
    protected WritableNftStore writableNftStore;
    /* ---------- Tokens ---------- */
    protected Token fungibleToken;
    protected Token fungibleTokenB;
    protected Token fungibleTokenC;
    protected Token nonFungibleToken;
    protected Nft nftSl1;
    protected Nft nftSl2;
    /* ---------- Token Relations ---------- */
    protected TokenRelation fungibleTokenRelation;
    protected TokenRelation nonFungibleTokenRelation;
    protected TokenRelation ownerFTRelation;
    protected TokenRelation ownerNFTRelation;
    protected TokenRelation treasuryFTRelation;
    protected TokenRelation treasuryNFTRelation;
    protected TokenRelation feeCollectorFTRelation;
    protected TokenRelation feeCollectorNFTRelation;
    protected TokenRelation ownerFTBRelation;
    protected TokenRelation ownerFTCRelation;
    protected TokenRelation feeCollectorFTBRelation;
    protected TokenRelation feeCollectorFTCRelation;

    /* ---------- Accounts ---------- */
    protected Account account;
    protected Account deleteAccount;
    protected Account transferAccount;
    protected Account ownerAccount;
    protected Account spenderAccount;
    protected Account delegatingSpenderAccount;
    protected Account treasuryAccount;

    private Map<AccountID, Account> accountsMap;
    private Map<Bytes, AccountID> aliasesMap;
    private Map<TokenID, Token> tokensMap;
    private Map<EntityIDPair, TokenRelation> tokenRelsMap;

    @Mock
    protected ReadableStates readableStates;

    @Mock
    protected WritableStates writableStates;

    protected Configuration configuration;
    protected VersionedConfigImpl versionedConfig;

    @BeforeEach
    public void setUp() {
        configuration = HederaTestConfigBuilder.createConfig();
        versionedConfig = new VersionedConfigImpl(configuration, 1);
        givenValidAccounts();
        givenValidTokens();
        givenValidTokenRelations();
        setUpAllEntities();
        refreshReadableStores();
    }

    private void setUpAllEntities() {
        accountsMap = new HashMap<>();
        accountsMap.put(payerId, account);
        accountsMap.put(deleteAccountId, deleteAccount);
        accountsMap.put(transferAccountId, transferAccount);
        accountsMap.put(ownerId, ownerAccount);
        accountsMap.put(delegatingSpenderId, delegatingSpenderAccount);
        accountsMap.put(spenderId, spenderAccount);
        accountsMap.put(treasuryId, treasuryAccount);

        tokensMap = new HashMap<>();
        tokensMap.put(fungibleTokenId, fungibleToken);
        tokensMap.put(nonFungibleTokenId, nonFungibleToken);
        tokensMap.put(fungibleTokenIDB, fungibleTokenB);
        tokensMap.put(fungibleTokenIDC, fungibleTokenC);

        aliasesMap = new HashMap<>();
        aliasesMap.put(alias.alias(), payerId);
        aliasesMap.put(contractAlias.evmAddress(), asAccount(contract.contractNum()));

        tokenRelsMap = new HashMap<>();
        tokenRelsMap.put(fungiblePair, fungibleTokenRelation);
        tokenRelsMap.put(nonFungiblePair, nonFungibleTokenRelation);
        tokenRelsMap.put(ownerFTPair, ownerFTRelation);
        tokenRelsMap.put(ownerNFTPair, ownerNFTRelation);
        tokenRelsMap.put(treasuryFTPair, treasuryFTRelation);
        tokenRelsMap.put(treasuryNFTPair, treasuryNFTRelation);
        tokenRelsMap.put(feeCollectorFTPair, feeCollectorFTRelation);
        tokenRelsMap.put(feeCollectorNFTPair, feeCollectorNFTRelation);
        tokenRelsMap.put(ownerFTBPair, ownerFTBRelation);
        tokenRelsMap.put(ownerFTCPair, ownerFTCRelation);
        tokenRelsMap.put(feeCollectorFTBPair, feeCollectorFTBRelation);
        tokenRelsMap.put(feeCollectorFTCPair, feeCollectorFTCRelation);
    }

    protected void basicMetaAssertions(final PreHandleContext context, final int keysSize) {
        assertThat(context.requiredNonPayerKeys()).hasSize(keysSize);
    }

    protected void refreshReadableStores() {
        givenAccountsInReadableStore();
        givenTokensInReadableStore();
        givenReadableTokenRelsStore();
        givenReadableNftStore();
    }

    protected void refreshWritableStores() {
        givenAccountsInWritableStore();
        givenTokensInWritableStore();
        givenWritableTokenRelsStore();
        givenWritableNftStore();
    }

    private void givenAccountsInReadableStore() {
        readableAccounts = readableAccountState();
        writableAccounts = emptyWritableAccountStateBuilder().build();
        readableAliases = readableAliasState();
        writableAliases = emptyWritableAliasStateBuilder().build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        given(readableStates.<Bytes, AccountID>get(ALIASES)).willReturn(readableAliases);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates);
        writableAccountStore = new WritableAccountStore(writableStates);
    }

    private void givenAccountsInWritableStore() {
        readableAccounts = readableAccountState();
        writableAccounts = writableAccountState();
        readableAliases = readableAliasState();
        writableAliases = writableAliasesState();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        given(readableStates.<Bytes, AccountID>get(ALIASES)).willReturn(readableAliases);
        given(writableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(writableAccounts);
        given(writableStates.<Bytes, AccountID>get(ALIASES)).willReturn(writableAliases);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates);
        writableAccountStore = new WritableAccountStore(writableStates);
    }

    private void givenTokensInReadableStore() {
        readableTokenState = readableTokenState();
        writableTokenState = emptyWritableTokenState();
        given(readableStates.<TokenID, Token>get(TOKENS)).willReturn(readableTokenState);
        given(writableStates.<TokenID, Token>get(TOKENS)).willReturn(writableTokenState);
        readableTokenStore = new ReadableTokenStoreImpl(readableStates);
        writableTokenStore = new WritableTokenStore(writableStates);
    }

    private void givenTokensInWritableStore() {
        readableTokenState = readableTokenState();
        writableTokenState = writableTokenState();
        given(readableStates.<TokenID, Token>get(TOKENS)).willReturn(readableTokenState);
        given(writableStates.<TokenID, Token>get(TOKENS)).willReturn(writableTokenState);
        readableTokenStore = new ReadableTokenStoreImpl(readableStates);
        writableTokenStore = new WritableTokenStore(writableStates);
    }

    private void givenReadableTokenRelsStore() {
        readableTokenRelState = readableTokenRelState();
        given(readableStates.<EntityIDPair, TokenRelation>get(TOKEN_RELS)).willReturn(readableTokenRelState);
        readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates);
    }

    private void givenWritableTokenRelsStore() {
        writableTokenRelState = writableTokenRelState();
        given(writableStates.<EntityIDPair, TokenRelation>get(TOKEN_RELS)).willReturn(writableTokenRelState);
        writableTokenRelStore = new WritableTokenRelationStore(writableStates);
    }

    private void givenReadableNftStore() {
        readableNftState = emptyReadableNftStateBuilder()
                .value(nftIdSl1, nftSl1)
                .value(nftIdSl2, nftSl2)
                .build();
        given(readableStates.<NftID, Nft>get(NFTS)).willReturn(readableNftState);
        readableNftStore = new ReadableNftStoreImpl(readableStates);
    }

    private void givenWritableNftStore() {
        writableNftState = emptyWritableNftStateBuilder()
                .value(nftIdSl1, nftSl1)
                .value(nftIdSl2, nftSl2)
                .build();
        given(writableStates.<NftID, Nft>get(NFTS)).willReturn(writableNftState);
        writableNftStore = new WritableNftStore(writableStates);
    }

    @NonNull
    protected MapWritableKVState<AccountID, Account> writableAccountState() {
        final var builder = emptyWritableAccountStateBuilder();
        for (final var entry : accountsMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @NonNull
    protected MapReadableKVState<AccountID, Account> readableAccountState() {
        final var builder = emptyReadableAccountStateBuilder();
        for (final var entry : accountsMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private MapWritableKVState<EntityIDPair, TokenRelation> writableTokenRelState() {
        final var builder = emptyWritableTokenRelsStateBuilder();
        for (final var entry : tokenRelsMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private MapReadableKVState<EntityIDPair, TokenRelation> readableTokenRelState() {
        final var builder = emptyReadableTokenRelsStateBuilder();
        for (final var entry : tokenRelsMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @NonNull
    protected MapWritableKVState<Bytes, AccountID> writableAliasesState() {
        final var builder = emptyWritableAliasStateBuilder();
        for (final var entry : aliasesMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @NonNull
    protected MapReadableKVState<Bytes, AccountID> readableAliasState() {
        final var builder = emptyReadableAliasStateBuilder();
        for (final var entry : aliasesMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @NonNull
    protected MapWritableKVState<TokenID, Token> writableTokenState() {
        final var builder = emptyWritableTokenStateBuilder();
        for (final var entry : tokensMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @NonNull
    protected MapReadableKVState<TokenID, Token> readableTokenState() {
        final var builder = emptyReadableTokenStateBuilder();
        for (final var entry : tokensMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private void givenValidTokenRelations() {
        fungibleTokenRelation = givenFungibleTokenRelation();
        nonFungibleTokenRelation = givenNonFungibleTokenRelation();
        ownerFTRelation =
                givenFungibleTokenRelation().copyBuilder().accountId(ownerId).build();
        ownerFTBRelation = givenFungibleTokenRelation()
                .copyBuilder()
                .accountId(ownerId)
                .tokenId(fungibleTokenIDB)
                .build();
        ownerFTCRelation = givenFungibleTokenRelation()
                .copyBuilder()
                .tokenId(fungibleTokenIDC)
                .accountId(ownerId)
                .build();
        ownerNFTRelation =
                givenNonFungibleTokenRelation().copyBuilder().accountId(ownerId).build();
        treasuryFTRelation =
                givenFungibleTokenRelation().copyBuilder().accountId(treasuryId).build();
        treasuryNFTRelation = givenNonFungibleTokenRelation()
                .copyBuilder()
                .accountId(treasuryId)
                .build();
        feeCollectorFTRelation = givenFungibleTokenRelation()
                .copyBuilder()
                .accountId(feeCollectorId)
                .build();
        feeCollectorNFTRelation = givenNonFungibleTokenRelation()
                .copyBuilder()
                .accountId(feeCollectorId)
                .build();
        feeCollectorFTBRelation = givenFungibleTokenRelation()
                .copyBuilder()
                .accountId(feeCollectorId)
                .tokenId(fungibleTokenIDB)
                .build();
        feeCollectorFTCRelation = givenFungibleTokenRelation()
                .copyBuilder()
                .tokenId(fungibleTokenIDC)
                .accountId(feeCollectorId)
                .build();
    }

    private void givenValidTokens() {
        fungibleToken = givenValidFungibleToken();
        fungibleTokenB = givenValidFungibleToken()
                .copyBuilder()
                .tokenId(fungibleTokenIDB)
                .customFees(withFixedFee(FixedFee.newBuilder()
                        .denominatingTokenId(fungibleTokenIDC)
                        .amount(1000)
                        .build()))
                .build();
        fungibleTokenC = givenValidFungibleToken()
                .copyBuilder()
                .tokenId(fungibleTokenIDC)
                .customFees(withFixedFee(FixedFee.newBuilder()
                        .denominatingTokenId(fungibleTokenId)
                        .amount(40)
                        .build()))
                .build();
        nonFungibleToken = givenValidNonFungibleToken();
        nftSl1 = givenNft(nftIdSl1);
        nftSl2 = givenNft(nftIdSl2);
    }

    private void givenValidAccounts() {
        account = givenValidAccount();
        spenderAccount = givenValidAccount()
                .copyBuilder()
                .key(spenderKey)
                .accountNumber(spenderId.accountNum())
                .build();
        ownerAccount = givenValidAccount()
                .copyBuilder()
                .accountNumber(ownerId.accountNum())
                .cryptoAllowances(AccountCryptoAllowance.newBuilder()
                        .spenderNum(spenderId.accountNum())
                        .amount(1000)
                        .build())
                .tokenAllowances(AccountFungibleTokenAllowance.newBuilder()
                        .tokenNum(fungibleTokenId.tokenNum())
                        .spenderNum(spenderId.accountNum())
                        .amount(1000)
                        .build())
                .approveForAllNftAllowances(AccountApprovalForAllAllowance.newBuilder()
                        .tokenNum(nonFungibleTokenId.tokenNum())
                        .spenderNum(spenderId.accountNum())
                        .build())
                .key(ownerKey)
                .build();
        delegatingSpenderAccount = givenValidAccount()
                .copyBuilder()
                .accountNumber(delegatingSpenderId.accountNum())
                .build();
        transferAccount = givenValidAccount()
                .copyBuilder()
                .accountNumber(transferAccountId.accountNum())
                .build();
        treasuryAccount = givenValidAccount()
                .copyBuilder()
                .accountNumber(treasuryId.accountNum())
                .key(treasuryKey)
                .build();
    }

    protected Token givenValidFungibleToken() {
        return givenValidFungibleToken(spenderId);
    }

    protected Token givenValidFungibleToken(AccountID autoRenewAccountId) {
        return givenValidFungibleToken(autoRenewAccountId, false, false, false, false);
    }

    protected Token givenValidFungibleToken(
            AccountID autoRenewAccountId,
            boolean deleted,
            boolean paused,
            boolean accountsFrozenByDefault,
            boolean accountsKycGrantedByDefault) {
        return new Token(
                fungibleTokenId,
                tokenName,
                tokenSymbol,
                1000,
                1000,
                treasuryId,
                adminKey,
                kycKey,
                freezeKey,
                wipeKey,
                supplyKey,
                feeScheduleKey,
                pauseKey,
                2,
                deleted,
                TokenType.FUNGIBLE_COMMON,
                TokenSupplyType.FINITE,
                autoRenewAccountId,
                autoRenewSecs,
                expirationTime,
                memo,
                100000,
                paused,
                accountsFrozenByDefault,
                accountsKycGrantedByDefault,
                customFees);
    }

    protected Token givenValidNonFungibleToken() {
        return fungibleToken
                .copyBuilder()
                .tokenId(nonFungibleTokenId)
                .treasuryAccountId(treasuryId)
                .customFees(List.of(withRoyaltyFee(royaltyFee)))
                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                .build();
    }

    protected Account givenValidAccount() {
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
                1,
                3,
                false,
                2,
                0,
                1000L,
                0,
                72000,
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                2,
                false,
                null);
    }

    protected TokenRelation givenFungibleTokenRelation() {
        return TokenRelation.newBuilder()
                .tokenId(fungibleTokenId)
                .accountId(payerId)
                .balance(1000L)
                .frozen(false)
                .kycGranted(true)
                .deleted(false)
                .automaticAssociation(true)
                .nextToken(asToken(2L))
                .previousToken(asToken(3L))
                .build();
    }

    protected TokenRelation givenNonFungibleTokenRelation() {
        return TokenRelation.newBuilder()
                .tokenId(nonFungibleTokenId)
                .accountId(payerId)
                .balance(1)
                .frozen(false)
                .kycGranted(true)
                .deleted(false)
                .automaticAssociation(true)
                .nextToken(asToken(2L))
                .previousToken(asToken(3L))
                .build();
    }

    protected Nft givenNft(NftID tokenID) {
        return Nft.newBuilder().ownerId(ownerId).id(tokenID).build();
    }

    protected CustomFee withFixedFee(final FixedFee fixedFee) {
        return CustomFee.newBuilder()
                .feeCollectorAccountId(feeCollectorId)
                .fixedFee(fixedFee)
                .build();
    }

    protected CustomFee withFractionalFee(final FractionalFee fractionalFee) {
        return CustomFee.newBuilder()
                .fractionalFee(fractionalFee)
                .feeCollectorAccountId(feeCollectorId)
                .build();
    }

    protected CustomFee withRoyaltyFee(final RoyaltyFee royaltyFee) {
        return CustomFee.newBuilder()
                .royaltyFee(royaltyFee)
                .feeCollectorAccountId(feeCollectorId)
                .build();
    }

    protected void givenStoresAndConfig(final HandleContext handleContext) {
        configuration = HederaTestConfigBuilder.createConfig();
        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(handleContext.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);

        given(handleContext.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(handleContext.readableStore(ReadableTokenStore.class)).willReturn(readableTokenStore);

        given(handleContext.readableStore(ReadableTokenRelationStore.class)).willReturn(readableTokenRelStore);
        given(handleContext.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

        given(handleContext.readableStore(ReadableNftStore.class)).willReturn(readableNftStore);
        given(handleContext.writableStore(WritableNftStore.class)).willReturn(writableNftStore);
    }
}
