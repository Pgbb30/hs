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

import static com.hedera.hapi.node.base.ResponseCodeEnum.*;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.handlers.CryptoApproveAllowanceHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.ApproveAllowanceValidator;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoApproveAllowanceHandlerTest extends CryptoTokenHandlerTestBase {
    @Mock(strictness = Strictness.LENIENT)
    private HandleContext handleContext;

    private CryptoApproveAllowanceHandler subject;

    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();
        final var validator = new ApproveAllowanceValidator();
        givenStoresAndConfig(handleContext);

        subject = new CryptoApproveAllowanceHandler(validator);
    }

    @Test
    void cryptoApproveAllowanceVanilla() throws PreCheckException {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 1);
        assertEquals(key, context.payerKey());
        assertThat(context.requiredNonPayerKeys()).containsExactlyInAnyOrder(ownerKey);
    }

    @Test
    void cryptoApproveAllowanceFailsWithInvalidOwner() throws PreCheckException {
        readableAccounts =
                emptyReadableAccountStateBuilder().value(payerId, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_ALLOWANCE_OWNER_ID);
    }

    @Test
    void cryptoApproveAllowanceDoesntAddIfOwnerSameAsPayer() throws PreCheckException {
        final var txn = cryptoApproveAllowanceTransaction(
                ownerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 0);
        assertEquals(ownerKey, context.payerKey());
        assertThat(context.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    void cryptoApproveAllowanceAddsDelegatingSpender() throws PreCheckException {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId, true, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 1);
        assertEquals(key, context.payerKey());
        assertThat(context.requiredNonPayerKeys()).containsExactlyInAnyOrder(ownerKey);
    }

    @Test
    void cryptoApproveAllowanceFailsIfDelegatingSpenderMissing() throws PreCheckException {
        readableAccounts = emptyReadableAccountStateBuilder()
                .value(payerId, account)
                .value(ownerId, ownerAccount)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId, true, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_DELEGATING_SPENDER);
    }

    @Test
    void happyPathAddsAllowances() {
        writableAccountStore.put(ownerAccount
                .copyBuilder()
                .cryptoAllowances(List.of())
                .tokenAllowances(List.of())
                .approveForAllNftAllowances(List.of())
                .build());
        given(handleContext.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance),
                List.of(nftAllowanceWithApproveForALl));
        given(handleContext.body()).willReturn(txn);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);

        assertThat(existingOwner.cryptoAllowances()).isEmpty();
        assertThat(existingOwner.tokenAllowances()).isEmpty();
        assertThat(existingOwner.approveForAllNftAllowances()).isEmpty();

        assertThat(writableNftStore.get(nftIdSl1)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl2)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNull();

        subject.handle(handleContext);

        final var modifiedOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(modifiedOwner.cryptoAllowances()).hasSize(1);
        assertThat(modifiedOwner.tokenAllowances()).hasSize(1);
        assertThat(modifiedOwner.approveForAllNftAllowances()).hasSize(1);

        assertThat(modifiedOwner.cryptoAllowances().get(0).spenderNum()).isEqualTo(spenderId.accountNum());
        assertThat(modifiedOwner.cryptoAllowances().get(0).amount()).isEqualTo(10);
        assertThat(modifiedOwner.tokenAllowances().get(0).spenderNum()).isEqualTo(spenderId.accountNum());
        assertThat(modifiedOwner.tokenAllowances().get(0).amount()).isEqualTo(10);
        assertThat(modifiedOwner.tokenAllowances().get(0).tokenNum()).isEqualTo(fungibleTokenId.tokenNum());
        assertThat(modifiedOwner.approveForAllNftAllowances().get(0).spenderNum())
                .isEqualTo(spenderId.accountNum());
        assertThat(modifiedOwner.approveForAllNftAllowances().get(0).tokenNum())
                .isEqualTo(nonFungibleTokenId.tokenNum());

        assertThat(writableNftStore.get(nftIdSl1)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl2)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isEqualTo(spenderId);
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isEqualTo(spenderId);
    }

    @Test
    void happyPathForUpdatingAllowances() {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        given(handleContext.body()).willReturn(txn);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);

        assertThat(existingOwner.cryptoAllowances()).hasSize(1);
        assertThat(existingOwner.tokenAllowances()).hasSize(1);
        assertThat(existingOwner.approveForAllNftAllowances()).hasSize(1);

        assertThat(existingOwner.cryptoAllowances().get(0).spenderNum()).isEqualTo(spenderId.accountNum());
        assertThat(existingOwner.cryptoAllowances().get(0).amount()).isEqualTo(1000);
        assertThat(existingOwner.tokenAllowances().get(0).spenderNum()).isEqualTo(spenderId.accountNum());
        assertThat(existingOwner.tokenAllowances().get(0).amount()).isEqualTo(1000);
        assertThat(existingOwner.tokenAllowances().get(0).tokenNum()).isEqualTo(fungibleTokenId.tokenNum());
        assertThat(existingOwner.approveForAllNftAllowances().get(0).spenderNum())
                .isEqualTo(spenderId.accountNum());
        assertThat(existingOwner.approveForAllNftAllowances().get(0).tokenNum())
                .isEqualTo(nonFungibleTokenId.tokenNum());

        assertThat(writableNftStore.get(nftIdSl1)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl2)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNull();

        subject.handle(handleContext);

        final var modifiedOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(modifiedOwner.cryptoAllowances()).hasSize(1);
        assertThat(modifiedOwner.tokenAllowances()).hasSize(1);
        assertThat(modifiedOwner.approveForAllNftAllowances()).hasSize(1);

        assertThat(modifiedOwner.cryptoAllowances().get(0).spenderNum()).isEqualTo(spenderId.accountNum());
        assertThat(modifiedOwner.cryptoAllowances().get(0).amount()).isEqualTo(10);
        assertThat(modifiedOwner.tokenAllowances().get(0).spenderNum()).isEqualTo(spenderId.accountNum());
        assertThat(modifiedOwner.tokenAllowances().get(0).amount()).isEqualTo(10);
        assertThat(modifiedOwner.tokenAllowances().get(0).tokenNum()).isEqualTo(fungibleTokenId.tokenNum());
        assertThat(modifiedOwner.approveForAllNftAllowances().get(0).spenderNum())
                .isEqualTo(spenderId.accountNum());
        assertThat(modifiedOwner.approveForAllNftAllowances().get(0).tokenNum())
                .isEqualTo(nonFungibleTokenId.tokenNum());

        assertThat(writableNftStore.get(nftIdSl1)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl2)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isEqualTo(spenderId);
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isEqualTo(spenderId);
    }

    @Test
    void settingApproveForAllToFalseRemovesAllowance() {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(),
                List.of(),
                List.of(nftAllowance.copyBuilder().approvedForAll(Boolean.FALSE).build()));
        given(handleContext.body()).willReturn(txn);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(existingOwner.approveForAllNftAllowances()).hasSize(1);

        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNull();

        subject.handle(handleContext);

        final var modifiedOwner = writableAccountStore.getAccountById(ownerId);

        assertThat(modifiedOwner.approveForAllNftAllowances()).isEmpty();
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isEqualTo(spenderId);
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isEqualTo(spenderId);
    }

    @Test
    void failsIfSenderDoesntOwnNFTSerial() {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(cryptoAllowance.copyBuilder().owner(AccountID.DEFAULT).build()),
                List.of(tokenAllowance.copyBuilder().owner(AccountID.DEFAULT).build()),
                List.of(nftAllowance.copyBuilder().owner(AccountID.DEFAULT).build()));

        given(handleContext.body()).willReturn(txn);
        final var payer = writableAccountStore.getAccountById(this.payerId);

        assertThat(payer.cryptoAllowances()).isEmpty();
        assertThat(payer.tokenAllowances()).isEmpty();
        assertThat(payer.approveForAllNftAllowances()).isEmpty();

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO));
    }

    @Test
    void treatsPayerAsOwnerIfOwnerNotSet() {
        // change the state to have the payer as owner for NFTs for a passing test.
        // If not it fails since those NFTs are not owned by the payer.
        writableNftState = emptyWritableNftStateBuilder()
                .value(nftIdSl1, nftSl1.copyBuilder().ownerId(payerId).build())
                .value(nftIdSl2, nftSl2.copyBuilder().ownerId(payerId).build())
                .build();
        given(writableStates.<NftID, Nft>get(NFTS)).willReturn(writableNftState);
        writableNftStore = new WritableNftStore(writableStates);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(cryptoAllowance.copyBuilder().owner(AccountID.DEFAULT).build()),
                List.of(tokenAllowance.copyBuilder().owner(AccountID.DEFAULT).build()),
                List.of(nftAllowance.copyBuilder().owner(AccountID.DEFAULT).build()));

        given(handleContext.body()).willReturn(txn);
        final var payer = writableAccountStore.getAccountById(this.payerId);

        assertThat(payer.cryptoAllowances()).isEmpty();
        assertThat(payer.tokenAllowances()).isEmpty();
        assertThat(payer.approveForAllNftAllowances()).isEmpty();

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO));

        final var modifiedPayer = writableAccountStore.getAccountById(this.payerId);

        final var newCryptoAllowances = modifiedPayer.cryptoAllowances();
        final var newTokenAllowances = modifiedPayer.tokenAllowances();
        final var newNftAllowances = modifiedPayer.approveForAllNftAllowances();

        assertThat(newCryptoAllowances).hasSize(1);
        assertThat(newTokenAllowances).hasSize(1);
        assertThat(newNftAllowances).isEmpty();

        assertThat(newCryptoAllowances.get(0).spenderNum()).isEqualTo(spenderId.accountNum());
        assertThat(newTokenAllowances.get(0).spenderNum()).isEqualTo(spenderId.accountNum());
    }

    @Test
    void checksIfAllowancesExceedLimit() {
        configuration = HederaTestConfigBuilder.create()
                .withValue("hedera.allowances.maxAccountLimit", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configuration);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        given(handleContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MAX_ALLOWANCES_EXCEEDED));
    }

    @Test
    void emptyAllowanceListInTransactionFails() throws PreCheckException {
        final var txn = cryptoApproveAllowanceTransaction(payerId, false, List.of(), List.of(), List.of());
        given(handleContext.body()).willReturn(txn);
        // Two know accounts we are using for these tests. Initial allowances
        final var existingPayer = writableAccountStore.getAccountById(payerId);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(existingPayer.cryptoAllowances()).isEmpty();
        assertThat(existingPayer.tokenAllowances()).isEmpty();
        assertThat(existingPayer.approveForAllNftAllowances()).isEmpty();
        assertThat(existingOwner.cryptoAllowances()).hasSize(1);
        assertThat(existingOwner.tokenAllowances()).hasSize(1);
        assertThat(existingOwner.approveForAllNftAllowances()).hasSize(1);

        final var preHandleContext = new FakePreHandleContext(readableAccountStore, txn);
        assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(EMPTY_ALLOWANCES));

        // After handle allowances are not modified
        final var afterHandlePayer = writableAccountStore.getAccountById(payerId);
        final var afterHandleOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(afterHandlePayer.cryptoAllowances()).isEmpty();
        assertThat(afterHandlePayer.tokenAllowances()).isEmpty();
        assertThat(afterHandlePayer.approveForAllNftAllowances()).isEmpty();
        assertThat(afterHandleOwner.cryptoAllowances()).hasSize(1);
        assertThat(afterHandleOwner.tokenAllowances()).hasSize(1);
        assertThat(afterHandleOwner.approveForAllNftAllowances()).hasSize(1);
    }

    @Test
    void newAllowancesWithAmountZeroAreNotAdded() {
        writableAccountStore.put(ownerAccount
                .copyBuilder()
                .cryptoAllowances(List.of())
                .tokenAllowances(List.of())
                .approveForAllNftAllowances(List.of())
                .build());
        given(handleContext.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(cryptoAllowance.copyBuilder().amount(0).build()),
                List.of(tokenAllowance.copyBuilder().amount(0).build()),
                List.of());
        given(handleContext.body()).willReturn(txn);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);

        assertThat(existingOwner.cryptoAllowances()).isEmpty();
        assertThat(existingOwner.tokenAllowances()).isEmpty();
        assertThat(existingOwner.approveForAllNftAllowances()).isEmpty();

        subject.handle(handleContext);

        final var modifiedOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(modifiedOwner.cryptoAllowances()).isEmpty();
        assertThat(modifiedOwner.tokenAllowances()).isEmpty();
        assertThat(existingOwner.approveForAllNftAllowances()).isEmpty();
    }

    @Test
    void existingAllowancesDeletedWithAmountZero() {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(cryptoAllowance.copyBuilder().amount(0).build()),
                List.of(tokenAllowance.copyBuilder().amount(0).build()),
                List.of());
        given(handleContext.body()).willReturn(txn);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);

        assertThat(existingOwner.cryptoAllowances()).hasSize(1);
        assertThat(existingOwner.tokenAllowances()).hasSize(1);

        subject.handle(handleContext);

        final var modifiedOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(modifiedOwner.cryptoAllowances()).isEmpty();
        assertThat(modifiedOwner.tokenAllowances()).isEmpty();
    }
    //
    //    @Test
    //    void failsToUpdateSpenderIfWrongOwner() {
    //        final var serials = List.of(1, 2);
    //
    //        AssertionsForClassTypes.assertThatThrownBy(() -> subject.updateSpender(
    //                        readableTokenStore, readableNftStore, ownerAccount, spenderId, nonFungibleTokenId,
    // serials))
    //                .isInstanceOf(HandleException.class)
    //                .has(responseCode(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO));
    //    }

    //
    //    @Test
    //    void updatesSpenderAsExpected() {
    //        nft1.setOwner(ownerId);
    //        nft2.setOwner(ownerId);
    //
    //        given(tokenStore.loadUniqueToken(tokenId, serial1)).willReturn(nft1);
    //        given(tokenStore.loadUniqueToken(tokenId, serial2)).willReturn(nft2);
    //        given(tokenStore.loadToken(tokenId)).willReturn(token);
    //        given(token.getTreasury()).willReturn(treasury);
    //        given(treasury.getId()).willReturn(ownerId);
    //
    //        updateSpender(tokenStore, ownerId, spenderId, tokenId, List.of(serial1, serial2));
    //
    //        assertEquals(spenderId, nft1.getSpender());
    //        assertEquals(spenderId, nft2.getSpender());
    //    }

    private TransactionBody cryptoApproveAllowanceTransaction(
            final AccountID id,
            final boolean isWithDelegatingSpender,
            final List<CryptoAllowance> cryptoAllowance,
            final List<TokenAllowance> tokenAllowance,
            final List<NftAllowance> nftAllowance) {
        final var transactionID = TransactionID.newBuilder().accountID(id).transactionValidStart(consensusTimestamp);
        final var allowanceTxnBody = CryptoApproveAllowanceTransactionBody.newBuilder()
                .cryptoAllowances(cryptoAllowance)
                .tokenAllowances(tokenAllowance)
                .nftAllowances(isWithDelegatingSpender ? List.of(nftAllowanceWithDelegatingSpender) : nftAllowance)
                .build();
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .cryptoApproveAllowance(allowanceTxnBody)
                .build();
    }
}
