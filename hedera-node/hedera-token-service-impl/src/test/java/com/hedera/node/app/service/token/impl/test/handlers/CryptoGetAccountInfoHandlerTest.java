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

package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.hapi.node.base.TokenFreezeStatus.FREEZE_NOT_APPLICABLE;
import static com.hedera.hapi.node.base.TokenKycStatus.KYC_NOT_APPLICABLE;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.ACCOUNTS_KEY;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.STAKING_INFO_KEY;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.TOKENS_KEY;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.TOKEN_RELS_KEY;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.StakingInfo;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenRelationship;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.AccountInfo;
import com.hedera.hapi.node.token.CryptoGetInfoQuery;
import com.hedera.hapi.node.token.CryptoGetInfoResponse;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableStakingInfoStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenRelationStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountInfoHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase;
import com.hedera.node.app.spi.fixtures.state.MapReadableKVState;
import com.hedera.node.app.spi.state.ReadableStates;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.CommonUtils;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoGetAccountInfoHandlerTest extends CryptoHandlerTestBase {

    @Mock(strictness = LENIENT)
    private QueryContext context;

    @Mock
    private Token token1, token2;
    @Mock
    private ReadableStates readableStates1, readableStates2, readableStates3, readableStates4;

    private CryptoGetAccountInfoHandler subject;

    @Mock
    private StakingNodeInfo stakingNodeInfo;

    @BeforeEach
    public void setUp() {
        super.setUp();
        subject = new CryptoGetAccountInfoHandler();
    }

    @Test
    @DisplayName("Query header is extracted correctly")
    void extractsHeader() {
        final var query = createCryptoGetInfoQuery(accountNum);
        final var header = subject.extractHeader(query);
        final var op = query.cryptoGetInfo();
        assertEquals(op.header(), header);
    }

    @Test
    @DisplayName("Check empty query response is created correctly")
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();
        final var response = subject.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .cryptoGetInfo(CryptoGetInfoResponse.newBuilder().header(responseHeader))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    @DisplayName("Validate query is successful with valid account")
    void validatesQueryWhenValidAccount() {
        readableAccounts = emptyReadableAccountStateBuilder().value(id, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(readableAccounts);
        readableStore = new ReadableAccountStoreImpl(readableStates);

        final var query = createCryptoGetInfoQuery(accountNum);
        given(context.query()).willReturn(query);
        given(context.createStore(ReadableAccountStore.class)).willReturn(readableStore);

        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Empty account failed during validate")
    void validatesQueryIfEmptyAccount() {
        final var state =
                MapReadableKVState.<AccountID, Account>builder(ACCOUNTS_KEY).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(state);
        final var store = new ReadableAccountStoreImpl(readableStates);

        final var query = createEmptyCryptoGetInfoQuery();

        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    @DisplayName("Account Id is needed during validate")
    void validatesQueryIfInvalidAccount() {
        final var state =
                MapReadableKVState.<AccountID, Account>builder(ACCOUNTS_KEY).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(state);
        final var store = new ReadableAccountStoreImpl(readableStates);

        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    @DisplayName("deleted account is not valid")
    void validatesQueryIfDeletedAccount() {
        deleteAccount = deleteAccount.copyBuilder().deleted(true).build();
        readableAccounts = emptyReadableAccountStateBuilder()
                .value(deleteAccountId, deleteAccount)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(readableAccounts);
        readableStore = new ReadableAccountStoreImpl(readableStates);

        final var query = createCryptoGetInfoQuery(deleteAccountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableStore);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.ACCOUNT_DELETED));
    }

    @Test
    @DisplayName("failed response is correctly handled in findResponse")
    void getsResponseIfFailedResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.INVALID_ACCOUNT_ID)
                .build();

        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(readableStore);

        setupConfig();

        final var response = subject.findResponse(context, responseHeader);
        final var op = response.cryptoGetInfo();
        assertEquals(ResponseCodeEnum.INVALID_ACCOUNT_ID, op.header().nodeTransactionPrecheckCode());
    }

    @Test
    @DisplayName("fail FAIL_INVALID test")
    void getsCorrectResponseHeadIfAccountInfoNotFound() {
        final var responseHeader = getOkResponse();

        setupAccountStore();
        setupTokenStore();
        setupTokenRelationStore();
        setupStakingInfoStore();
        setupConfig();

        final var query = createCryptoGetInfoQuery(4);
        when(context.query()).thenReturn(query);

        final var response = subject.findResponse(context, responseHeader);
        final var cryptoGetInfoResponse = response.cryptoGetInfo();
        assertEquals(getFailInvalidResponse(), cryptoGetInfoResponse.header());
    }

    @Test
    @DisplayName("OK response is correctly handled in findResponse")
    void getsResponseIfOkResponse() {
        final var responseHeader = getOkResponse();
        final var expectedInfo = getExpectedAccountInfo();

        account = account.copyBuilder().stakedNodeId(0).declineReward(false).build();
        setupAccountStore();

        given(token1.decimals()).willReturn(100);
        given(token1.symbol()).willReturn("FOO");
        given(token1.tokenId()).willReturn(asToken(3L));
        setupTokenStore(token1);

        final var tokenRelation = TokenRelation.newBuilder()
                .tokenId(asToken(3L))
                .accountId(id)
                .balance(1000L)
                .frozen(false)
                .kycGranted(false)
                .deleted(false)
                .automaticAssociation(true)
                .nextToken(asToken(4L))
                .previousToken(asToken(2L))
                .build();
        setupTokenRelationStore(tokenRelation);
        setupStakingInfoStore();
        setupConfig();
        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);

        final var response = subject.findResponse(context, responseHeader);
        final var cryptoGetInfoResponse = response.cryptoGetInfo();
        assertEquals(ResponseCodeEnum.OK, cryptoGetInfoResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, cryptoGetInfoResponse.accountInfo());
    }

    @Test
    @DisplayName("check multiple token relations list")
    void checkMulitpleTokenRelations() {
        final var responseHeader = getOkResponse();
        final var expectedInfo = getExpectedAccountInfos();

        account = account.copyBuilder().stakedNodeId(0).declineReward(false).build();
        setupAccountStore();

        given(token1.decimals()).willReturn(100);
        given(token2.decimals()).willReturn(50);
        given(token1.symbol()).willReturn("FOO");
        given(token2.symbol()).willReturn("BAR");
        given(token1.tokenId()).willReturn(asToken(3L));
        given(token2.tokenId()).willReturn(asToken(4L));
        setupTokenStore(token1, token2);

        final var tokenRelation1 = TokenRelation.newBuilder()
                .tokenId(asToken(3L))
                .accountId(id)
                .balance(1000L)
                .frozen(false)
                .kycGranted(false)
                .deleted(false)
                .automaticAssociation(true)
                .nextToken(asToken(4L))
                .previousToken(asToken(2L))
                .build();
        final var tokenRelation2 = TokenRelation.newBuilder()
                .tokenId(asToken(4L))
                .accountId(id)
                .balance(100L)
                .frozen(false)
                .kycGranted(false)
                .deleted(false)
                .automaticAssociation(true)
                .nextToken(asToken(5L))
                .previousToken(asToken(3L))
                .build();
        final var tokenRelation3 = TokenRelation.newBuilder()
                .tokenId(asToken(5L))
                .accountId(id)
                .balance(10L)
                .frozen(false)
                .kycGranted(false)
                .deleted(false)
                .automaticAssociation(true)
                .nextToken(asToken(6L))
                .previousToken(asToken(4L))
                .build();
        setupTokenRelationStore(tokenRelation1, tokenRelation2, tokenRelation3);
        setupStakingInfoStore();
        setupConfig();

        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);

        final var response = subject.findResponse(context, responseHeader);
        final var cryptoGetInfoResponse = response.cryptoGetInfo();

        assertEquals(ResponseCodeEnum.OK, cryptoGetInfoResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, cryptoGetInfoResponse.accountInfo());
        assertEquals(2, cryptoGetInfoResponse.accountInfo().tokenRelationships().size());
    }

    @Test
    void testStakeNumber() {
        final var responseHeader = getOkResponse();
        final var expectedInfo = getExpectedAccountInfo2();

        account = account.copyBuilder()
                .stakedAccountId(AccountID.newBuilder().accountNum(1).build())
                .declineReward(false)
                .build();
        setupAccountStore();

        given(token1.decimals()).willReturn(100);
        given(token1.symbol()).willReturn("FOO");
        given(token1.tokenId()).willReturn(asToken(3L));
        setupTokenStore(token1);

        final var tokenRelation = TokenRelation.newBuilder()
                .tokenId(asToken(3L))
                .accountId(id)
                .balance(1000L)
                .frozen(false)
                .kycGranted(false)
                .deleted(false)
                .automaticAssociation(true)
                .nextToken(asToken(4L))
                .previousToken(asToken(2L))
                .build();
        setupTokenRelationStore(tokenRelation);
        setupStakingInfoStore();
        setupConfig();
        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);

        final var response = subject.findResponse(context, responseHeader);
        final var cryptoGetInfoResponse = response.cryptoGetInfo();
        assertEquals(ResponseCodeEnum.OK, cryptoGetInfoResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, cryptoGetInfoResponse.accountInfo());
    }

    @Test
    void testEvmAddressAlias() {
        final Bytes evmAddress = Bytes.wrap(CommonUtils.unhex("6aeb3773ea468a814d954e6dec795bfee7d76e26"));
        final var responseHeader = getOkResponse();
        final var expectedInfo = getExpectedAccountInfoEvm(evmAddress);

        account = account.copyBuilder()
                .stakedNodeId(0)
                .declineReward(false)
                .alias(evmAddress)
                .build();
        setupAccountStore();

        given(token1.decimals()).willReturn(100);
        given(token1.symbol()).willReturn("FOO");
        given(token1.tokenId()).willReturn(asToken(3L));
        setupTokenStore(token1);

        final var tokenRelation = TokenRelation.newBuilder()
                .tokenId(asToken(3L))
                .accountId(id)
                .balance(1000L)
                .frozen(false)
                .kycGranted(false)
                .deleted(false)
                .automaticAssociation(true)
                .nextToken(asToken(4L))
                .previousToken(asToken(2L))
                .build();
        setupTokenRelationStore(tokenRelation);
        setupStakingInfoStore();
        setupConfig();
        final var query = createCryptoGetInfoQuery(accountNum);
        when(context.query()).thenReturn(query);

        final var response = subject.findResponse(context, responseHeader);
        final var cryptoGetInfoResponse = response.cryptoGetInfo();
        assertEquals(ResponseCodeEnum.OK, cryptoGetInfoResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, cryptoGetInfoResponse.accountInfo());
    }

    private void setupAccountStore() {
        final var readableAccounts = MapReadableKVState.<AccountID, Account>builder(ACCOUNTS_KEY)
                .value(id, account)
                .build();
        given(readableStates1.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(readableAccounts);
        ReadableAccountStore ReadableAccountStore = new ReadableAccountStoreImpl(readableStates1);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(ReadableAccountStore);
    }

    private void setupTokenStore(Token... tokens) {
        final var readableToken = MapReadableKVState.<TokenID, Token>builder(TOKENS_KEY);
        for (Token token : tokens) {
            readableToken.value(token.tokenId(), token);
        }
        given(readableStates2.<TokenID, Token>get(TOKENS_KEY)).willReturn(readableToken.build());
        final var readableTokenStore = new ReadableTokenStoreImpl(readableStates2);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
    }

    private void setupTokenRelationStore(TokenRelation... tokenRelations) {
        final var readableTokenRel = MapReadableKVState.<EntityIDPair, TokenRelation>builder(TOKEN_RELS_KEY);
        for (TokenRelation tokenRelation : tokenRelations) {
            readableTokenRel.value(
                    EntityIDPair.newBuilder()
                            .accountId(id)
                            .tokenId(tokenRelation.tokenId())
                            .build(),
                    tokenRelation);
        }
        given(readableStates3.<EntityIDPair, TokenRelation>get(TOKEN_RELS_KEY)).willReturn(readableTokenRel.build());
        final var readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates3);
        when(context.createStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);
    }

    private void setupStakingInfoStore() {
        final var readableStakingNodes = MapReadableKVState.<AccountID, StakingNodeInfo>builder(STAKING_INFO_KEY)
                .value(id, stakingNodeInfo)
                .build();
        given(readableStates4.<AccountID, StakingNodeInfo>get(STAKING_INFO_KEY)).willReturn(readableStakingNodes);
        final var readableStakingInfoStore = new ReadableStakingInfoStoreImpl(readableStates4);
        when(context.createStore(ReadableStakingInfoStore.class)).thenReturn(readableStakingInfoStore);
    }

    private void setupConfig() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.maxRelsPerInfoQuery", 2)
                .withValue("ledger.id", "0x03")
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);
    }

    private AccountInfo getExpectedAccountInfo() {
        return AccountInfo.newBuilder()
                .key(key)
                .accountID(id)
                .receiverSigRequired(true)
                .ledgerId(new BytesConverter().convert("0x03"))
                .deleted(false)
                .memo("testAccount")
                .autoRenewPeriod(Duration.newBuilder().seconds(72000))
                .balance(payerBalance)
                .expirationTime(Timestamp.newBuilder().seconds(1_234_567L))
                .ownedNfts(2)
                .maxAutomaticTokenAssociations(10)
                .ethereumNonce(0)
                .alias(alias.alias())
                .contractAccountID("0000000000000000000000000000000000000003")
                .tokenRelationships(getExpectedTokenRelationship())
                .stakingInfo(getExpectedStakingInfo())
                .build();
    }

    private AccountInfo getExpectedAccountInfo2() {
        return AccountInfo.newBuilder()
                .key(key)
                .accountID(id)
                .receiverSigRequired(true)
                .ledgerId(new BytesConverter().convert("0x03"))
                .deleted(false)
                .memo("testAccount")
                .autoRenewPeriod(Duration.newBuilder().seconds(72000))
                .balance(payerBalance)
                .expirationTime(Timestamp.newBuilder().seconds(1_234_567L))
                .ownedNfts(2)
                .maxAutomaticTokenAssociations(10)
                .ethereumNonce(0)
                .alias(alias.alias())
                .contractAccountID("0000000000000000000000000000000000000003")
                .tokenRelationships(getExpectedTokenRelationship())
                .stakingInfo(getExpectedStakingInfo2())
                .build();
    }

    private AccountInfo getExpectedAccountInfoEvm(Bytes evmAddress) {
        return AccountInfo.newBuilder()
                .key(key)
                .accountID(id)
                .receiverSigRequired(true)
                .ledgerId(new BytesConverter().convert("0x03"))
                .deleted(false)
                .memo("testAccount")
                .autoRenewPeriod(Duration.newBuilder().seconds(72000))
                .balance(payerBalance)
                .expirationTime(Timestamp.newBuilder().seconds(1_234_567L))
                .ownedNfts(2)
                .maxAutomaticTokenAssociations(10)
                .ethereumNonce(0)
                .alias(evmAddress)
                .contractAccountID("6aeb3773ea468a814d954e6dec795bfee7d76e26")
                .tokenRelationships(getExpectedTokenRelationship())
                .stakingInfo(getExpectedStakingInfo())
                .build();
    }

    private AccountInfo getExpectedAccountInfos() {
        return AccountInfo.newBuilder()
                .key(key)
                .accountID(id)
                .receiverSigRequired(true)
                .ledgerId(new BytesConverter().convert("0x03"))
                .deleted(false)
                .memo("testAccount")
                .autoRenewPeriod(Duration.newBuilder().seconds(72000))
                .balance(payerBalance)
                .expirationTime(Timestamp.newBuilder().seconds(1_234_567L))
                .ownedNfts(2)
                .maxAutomaticTokenAssociations(10)
                .ethereumNonce(0)
                .alias(alias.alias())
                .contractAccountID("0000000000000000000000000000000000000003")
                .tokenRelationships(getExpectedTokenRelationships())
                .stakingInfo(getExpectedStakingInfo())
                .build();
    }

    private List<TokenRelationship> getExpectedTokenRelationship() {
        var ret = new ArrayList<TokenRelationship>();
        final var tokenRelationship1 = TokenRelationship.newBuilder()
                .tokenId(TokenID.newBuilder().tokenNum(3L).build())
                .symbol("FOO")
                .balance(1000)
                .decimals(100)
                .kycStatus(KYC_NOT_APPLICABLE)
                .freezeStatus(FREEZE_NOT_APPLICABLE)
                .automaticAssociation(true)
                .build();
        ret.add(tokenRelationship1);
        return ret;
    }

    private List<TokenRelationship> getExpectedTokenRelationships() {
        var ret = new ArrayList<TokenRelationship>();
        final var tokenRelationship1 = TokenRelationship.newBuilder()
                .tokenId(TokenID.newBuilder().tokenNum(3L).build())
                .symbol("FOO")
                .balance(1000)
                .decimals(100)
                .kycStatus(KYC_NOT_APPLICABLE)
                .freezeStatus(FREEZE_NOT_APPLICABLE)
                .automaticAssociation(true)
                .build();
        final var tokenRelationship2 = TokenRelationship.newBuilder()
                .tokenId(TokenID.newBuilder().tokenNum(4L).build())
                .symbol("BAR")
                .balance(100)
                .decimals(50)
                .kycStatus(KYC_NOT_APPLICABLE)
                .freezeStatus(FREEZE_NOT_APPLICABLE)
                .automaticAssociation(true)
                .build();

        ret.add(tokenRelationship1);
        ret.add(tokenRelationship2);
        return ret;
    }

    private StakingInfo getExpectedStakingInfo() {
        return StakingInfo.newBuilder()
                .declineReward(false)
                .stakedToMe(1_234L)
                .stakedNodeId(0)
                .stakePeriodStart(Timestamp.newBuilder().seconds(0))
                .build();
    }

    private StakingInfo getExpectedStakingInfo2() {
        return StakingInfo.newBuilder()
                .declineReward(false)
                .stakedToMe(1_234L)
                .stakedAccountId(AccountID.newBuilder().accountNum(1).build())
                .build();
    }

    private ResponseHeader getFailInvalidResponse() {
        return ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(FAIL_INVALID)
                .responseType(ANSWER_ONLY)
                .cost(0)
                .build();
    }

    private Query createCryptoGetInfoQuery(final long accountId) {
        final var data = CryptoGetInfoQuery.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(accountId).build())
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().cryptoGetInfo(data).build();
    }

    private Query createEmptyCryptoGetInfoQuery() {
        final var data = CryptoGetInfoQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().cryptoGetInfo(data).build();
    }

    private ResponseHeader getOkResponse() {
        return ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
    }
}
