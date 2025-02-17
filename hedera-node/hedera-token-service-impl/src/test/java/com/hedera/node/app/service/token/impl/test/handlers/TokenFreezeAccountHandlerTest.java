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

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.node.app.service.mono.pbj.PbjConverter.toPbj;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.test.factories.scenarios.TokenFreezeScenarios.FREEZE_WITH_NO_KEYS;
import static com.hedera.test.factories.scenarios.TokenFreezeScenarios.VALID_FREEZE_WITH_EXTANT_TOKEN;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.FIRST_TOKEN_SENDER_KT;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.KNOWN_TOKEN_NO_SPECIAL_KEYS;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.KNOWN_TOKEN_WITH_FREEZE;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.TOKEN_FREEZE_KT;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenFreezeAccountTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.handlers.TokenFreezeAccountHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.ParityTestBase;
import com.hedera.node.app.spi.fixtures.Assertions;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

class TokenFreezeAccountHandlerTest {
    private static final AccountID ACCOUNT_13257 =
            AccountID.newBuilder().accountNum(13257).build();

    private TokenFreezeAccountHandler subject;

    @BeforeEach
    void setUp() {
        subject = new TokenFreezeAccountHandler();
    }

    @Nested
    class PreHandleTests extends ParityTestBase {
        @Test
        void tokenFreezeWithExtantTokenScenario() throws PreCheckException {
            final var theTxn = txnFrom(VALID_FREEZE_WITH_EXTANT_TOKEN);

            final var context = new FakePreHandleContext(readableAccountStore, theTxn);
            context.registerStore(ReadableTokenStore.class, readableTokenStore);
            subject.preHandle(context);

            assertEquals(1, context.requiredNonPayerKeys().size());
            assertThat(context.requiredNonPayerKeys(), contains(TOKEN_FREEZE_KT.asPbjKey()));
        }

        @Test
        void tokenFreezeWithNoToken() throws PreCheckException {
            final var theTxn = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_13257))
                    .tokenFreeze(TokenFreezeAccountTransactionBody.newBuilder().account(ACCOUNT_13257))
                    .build();

            final var context = new FakePreHandleContext(readableAccountStore, theTxn);
            context.registerStore(ReadableTokenStore.class, readableTokenStore);
            Assertions.assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_TOKEN_ID);
        }

        @Test
        void tokenFreezeWithNoAccount() throws PreCheckException {
            final var theTxn = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_13257))
                    .tokenFreeze(TokenFreezeAccountTransactionBody.newBuilder()
                            .token(TokenID.newBuilder().tokenNum(123L)))
                    .build();

            final var context = new FakePreHandleContext(readableAccountStore, theTxn);
            context.registerStore(ReadableTokenStore.class, readableTokenStore);
            Assertions.assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_ACCOUNT_ID);
        }

        @Test
        void tokenFreezeWithNoFreezeKey() throws PreCheckException {
            final var theTxn = txnFrom(FREEZE_WITH_NO_KEYS);

            final var context = new FakePreHandleContext(readableAccountStore, theTxn);
            context.registerStore(ReadableTokenStore.class, readableTokenStore);
            Assertions.assertThrowsPreCheck(() -> subject.preHandle(context), TOKEN_HAS_NO_FREEZE_KEY);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class HandleTests {
        private static final TokenID MISSING_TOKEN_12345 =
                TokenID.newBuilder().tokenNum(12345).build();

        @Mock(strictness = Strictness.LENIENT)
        private HandleContext context;

        @Mock
        private ReadableTokenStore readableTokenStore;

        @Mock
        private ReadableAccountStore readableAccountStore;

        @Mock
        private WritableTokenRelationStore tokenRelStore;

        @BeforeEach
        void setup() {
            given(context.readableStore(ReadableTokenStore.class)).willReturn(readableTokenStore);
            given(context.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
            given(context.writableStore(WritableTokenRelationStore.class)).willReturn(tokenRelStore);
        }

        @SuppressWarnings("DataFlowIssue")
        @Test
        void nullArgThrowsException() {
            assertThatThrownBy(() -> subject.handle(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void tokenNotPresentInTxnBody() {
            final var noTokenTxn = newFreezeTxn(null, ACCOUNT_13257);
            given(context.body()).willReturn(noTokenTxn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TOKEN_ID));
            verifyNoPut();
        }

        @Test
        void accountNotPresentInTxnBody() {
            final var pbjToken = toPbj(KNOWN_TOKEN_WITH_FREEZE);
            final var noAcctTxn = newFreezeTxn(pbjToken, null);
            given(readableTokenStore.getTokenMeta(pbjToken)).willReturn(tokenMetaWithFreezeKey());
            given(context.body()).willReturn(noAcctTxn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
            verifyNoPut();
        }

        @Test
        void tokenNotFound() {
            final var token = MISSING_TOKEN_12345;
            given(readableTokenStore.getTokenMeta(token)).willReturn(null);
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TOKEN_ID));
            verifyNoPut();
        }

        @Test
        void tokenHasNoFreezeKey() {
            final var token = toPbj(KNOWN_TOKEN_NO_SPECIAL_KEYS);
            given(readableTokenStore.getTokenMeta(token)).willReturn(tokenMetaWithFreezeKey(null));
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_HAS_NO_FREEZE_KEY));
            verifyNoPut();
        }

        @Test
        void accountNotFound() {
            final var token = toPbj(KNOWN_TOKEN_WITH_FREEZE);
            given(readableTokenStore.getTokenMeta(token)).willReturn(tokenMetaWithFreezeKey());
            given(readableAccountStore.getAccountById(ACCOUNT_13257)).willReturn(null);
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
            verifyNoPut();
        }

        @Test
        void tokenRelNotFound() throws HandleException {
            final var token = toPbj(KNOWN_TOKEN_WITH_FREEZE);
            final var accountNumber = (long) ACCOUNT_13257.accountNumOrThrow();
            given(readableTokenStore.getTokenMeta(token)).willReturn(tokenMetaWithFreezeKey());
            given(readableAccountStore.getAccountById(ACCOUNT_13257))
                    .willReturn(
                            Account.newBuilder().accountNumber(accountNumber).build());
            given(tokenRelStore.getForModify(ACCOUNT_13257, token)).willReturn(null);
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
            verifyNoPut();
        }

        @Test
        void tokenRelFreezeSuccessful() {
            final var token = toPbj(KNOWN_TOKEN_WITH_FREEZE);
            final var accountNumber = (long) ACCOUNT_13257.accountNumOrThrow();
            given(readableTokenStore.getTokenMeta(token)).willReturn(tokenMetaWithFreezeKey());
            given(readableAccountStore.getAccountById(ACCOUNT_13257))
                    .willReturn(
                            Account.newBuilder().accountNumber(accountNumber).build());
            given(tokenRelStore.getForModify(ACCOUNT_13257, token))
                    .willReturn(TokenRelation.newBuilder()
                            .tokenId(token)
                            .accountId(ACCOUNT_13257)
                            .build());
            final var txn = newFreezeTxn(token);
            given(context.body()).willReturn(txn);

            subject.handle(context);
            verify(tokenRelStore)
                    .put(TokenRelation.newBuilder()
                            .tokenId(token)
                            .accountId(ACCOUNT_13257)
                            .frozen(true)
                            .build());
        }

        private HandleContext mockContext() {
            final var context = mock(HandleContext.class);
            given(context.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
            given(context.readableStore(ReadableTokenStore.class)).willReturn(readableTokenStore);

            return context;
        }

        private void verifyNoPut() {
            verify(tokenRelStore, never()).put(any());
        }

        private ReadableTokenStore.TokenMetadata tokenMetaWithFreezeKey() {
            return tokenMetaWithFreezeKey(FIRST_TOKEN_SENDER_KT.asPbjKey());
        }

        private ReadableTokenStore.TokenMetadata tokenMetaWithFreezeKey(Key freezeKey) {
            return new ReadableTokenStore.TokenMetadata(
                    null, null, null, freezeKey, null, null, null, null, false, asAccount(25L), 2);
        }

        private TransactionBody newFreezeTxn(TokenID token) {
            return newFreezeTxn(token, ACCOUNT_13257);
        }

        private TransactionBody newFreezeTxn(TokenID token, AccountID account) {
            TokenFreezeAccountTransactionBody.Builder freezeTxnBodyBuilder =
                    TokenFreezeAccountTransactionBody.newBuilder();
            if (token != null) freezeTxnBodyBuilder.token(token);
            if (account != null) freezeTxnBodyBuilder.account(account);
            return TransactionBody.newBuilder()
                    .transactionID(
                            TransactionID.newBuilder().accountID(ACCOUNT_13257).build())
                    .tokenFreeze(freezeTxnBodyBuilder)
                    .build();
        }
    }
}
