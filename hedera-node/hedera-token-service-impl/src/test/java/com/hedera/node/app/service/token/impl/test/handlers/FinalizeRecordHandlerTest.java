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
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.handlers.FinalizeRecordHandler;
import com.hedera.node.app.service.token.impl.records.CryptoTransferRecordBuilder;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.mockito.Mock;

class FinalizeRecordHandlerTest extends CryptoTokenHandlerTestBase {
    private final Account ACCOUNT_1212 =
            givenValidAccount().copyBuilder().accountNumber(1212).build();
    private final AccountID ACCOUNT_1212_ID = asAccountId(ACCOUNT_1212);
    private final Account ACCOUNT_3434 = givenValidAccount()
            .copyBuilder()
            .accountNumber(3434)
            .tinybarBalance(500)
            .build();
    private final AccountID ACCOUNT_3434_ID = asAccountId(ACCOUNT_3434);
    private final Account ACCOUNT_5656 = givenValidAccount()
            .copyBuilder()
            .accountNumber(5656)
            .tinybarBalance(10000)
            .build();
    private final AccountID ACCOUNT_5656_ID = asAccountId(ACCOUNT_5656);
    private static final TokenID TOKEN_321 = asToken(321);

    @Mock(strictness = LENIENT)
    private HandleContext context;

    @Mock
    private CryptoTransferRecordBuilder recordBuilder;

    private ReadableAccountStore readableAccountStore;
    private WritableAccountStore writableAccountStore;
    private ReadableNftStore readableNftStore;
    private WritableNftStore writableNftStore;

    private FinalizeRecordHandler subject;

    @BeforeEach
    public void setUp() {
        subject = new FinalizeRecordHandler();
    }

    @Test
    void pureChecksSucceeds() {
        Assertions.assertThatCode(() -> subject.pureChecks(mock(TransactionBody.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void prehandleSucceeds() {
        Assertions.assertThatCode(() -> subject.preHandle(mock(PreHandleContext.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void handleNullArg() {
        assertThatThrownBy(() -> subject.handle(context)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void handleHbarNetTransferAmountIsNotZero() {
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(ACCOUNT_1212);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(ACCOUNT_1212);
        writableAccountStore.put(ACCOUNT_1212
                .copyBuilder()
                .tinybarBalance(ACCOUNT_1212.tinybarBalance() - 5)
                .build());
        context = mockContext();

        assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(FAIL_INVALID));
    }

    @Test
    void handleHbarAccountBalanceIsNegative() {
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434);
        // This amount will cause the net transfer amount to be negative for account 1212
        final var amountToAdjust = ACCOUNT_1212.tinybarBalance() + 1;
        writableAccountStore.put(ACCOUNT_1212
                .copyBuilder()
                .tinybarBalance(ACCOUNT_1212.tinybarBalance() - amountToAdjust)
                .build());
        writableAccountStore.put(ACCOUNT_3434
                .copyBuilder()
                .tinybarBalance(ACCOUNT_3434.tinybarBalance() + amountToAdjust)
                .build());
        context = mockContext();

        assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(FAIL_INVALID));
    }

    @Test
    void handleHbarAccountBalanceDoesntChange() {
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(ACCOUNT_1212);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(ACCOUNT_1212);
        readableNftStore = TestStoreFactory.newReadableStoreWithNfts(); // Intentionally empty
        writableNftStore = TestStoreFactory.newWritableStoreWithNfts(); // Intentionally empty
        // Account 1212 changes by getting a new memo, but its balance doesn't change
        writableAccountStore.put(
                ACCOUNT_1212.copyBuilder().memo("different memo field").build());
        // Intentionally empty token rel store
        writableTokenRelStore = TestStoreFactory.newWritableStoreWithTokenRels();
        context = mockContext();

        subject.handle(context);

        BDDMockito.verifyNoInteractions(recordBuilder);
    }

    @Test
    void handleHbarTransfersToNewAccountSuccess() {
        // This case handles a successful hbar transfer to an auto-created account

        final var amountToTransfer = ACCOUNT_1212.tinybarBalance() - 1;
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(ACCOUNT_1212);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(ACCOUNT_1212);
        writableAccountStore.put(ACCOUNT_1212.copyBuilder().tinybarBalance(1).build());
        // Putting ACCOUNT_3434 into the writable store here simulates this account being auto-created
        writableAccountStore.put(ACCOUNT_3434
                .copyBuilder()
                .alias(Bytes.wrap("00000000000000000001"))
                .tinybarBalance(amountToTransfer)
                .build());
        readableTokenRelStore = TestStoreFactory.newReadableStoreWithTokenRels(); // Intentionally empty
        writableTokenRelStore = TestStoreFactory.newWritableStoreWithTokenRels(); // Intentionally empty
        readableNftStore = TestStoreFactory.newReadableStoreWithNfts(); // Intentionally empty
        writableNftStore = TestStoreFactory.newWritableStoreWithNfts(); // Intentionally empty
        context = mockContext();

        subject.handle(context);

        BDDMockito.verify(recordBuilder)
                .transferList(TransferList.newBuilder()
                        .accountAmounts(
                                AccountAmount.newBuilder()
                                        .accountID(ACCOUNT_1212_ID)
                                        .amount(-amountToTransfer)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(ACCOUNT_3434_ID)
                                        .amount(amountToTransfer)
                                        .build())
                        .build());
    }

    @Test
    void handleHbarTransfersToExistingAccountSuccess() {
        // This test case handles successfully transferring hbar only (no tokens)

        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434, ACCOUNT_5656);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434, ACCOUNT_5656);
        writableTokenRelStore = TestStoreFactory.newWritableStoreWithTokenRels(); // Intentionally empty
        readableNftStore = TestStoreFactory.newReadableStoreWithNfts(); // Intentionally empty
        writableNftStore = TestStoreFactory.newWritableStoreWithNfts(); // Intentionally empty
        final var acct1212Change = 10;
        writableAccountStore.put(ACCOUNT_1212
                .copyBuilder()
                .tinybarBalance(ACCOUNT_1212.tinybarBalance() - acct1212Change)
                .build());
        writableAccountStore.put(ACCOUNT_3434
                .copyBuilder()
                .tinybarBalance(ACCOUNT_3434.tinybarBalance() + acct1212Change)
                .build());
        // Account 5656 changes by getting a new memo, but its balance doesn't change
        writableAccountStore.put(
                ACCOUNT_5656.copyBuilder().memo("different memo field").build());
        context = mockContext();

        subject.handle(context);

        BDDMockito.verify(recordBuilder)
                .transferList(TransferList.newBuilder()
                        .accountAmounts(
                                AccountAmount.newBuilder()
                                        .accountID(ACCOUNT_1212_ID)
                                        .amount(-acct1212Change)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(ACCOUNT_3434_ID)
                                        .amount(acct1212Change)
                                        .build())
                        // There shouldn't be any entry for account 5656 because its balance didn't change
                        .build());
    }

    @Test
    void handleFungibleTokenBalanceIsNegative() {
        final var validAcct = givenValidAccount();
        final var tokenRel = givenFungibleTokenRelation(); // Already tied to validAcct's account ID
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(validAcct);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(validAcct);
        readableTokenRelStore = TestStoreFactory.newReadableStoreWithTokenRels(tokenRel);
        writableTokenRelStore = TestStoreFactory.newWritableStoreWithTokenRels(tokenRel);
        writableTokenRelStore.put(tokenRel.copyBuilder().balance(-1).build());
        context = mockContext();

        assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(FAIL_INVALID));
    }

    @Test
    void handleFungibleTransferTokenBalancesDontChange() {
        final var validAcct = givenValidAccount();
        final var tokenRel = givenFungibleTokenRelation();
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(validAcct);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(validAcct);
        readableTokenRelStore = TestStoreFactory.newReadableStoreWithTokenRels(tokenRel);
        writableTokenRelStore = TestStoreFactory.newWritableStoreWithTokenRels(tokenRel);
        readableNftStore = TestStoreFactory.newReadableStoreWithNfts(); // Intentionally empty
        writableNftStore = TestStoreFactory.newWritableStoreWithNfts(); // Intentionally empty
        // The token relation's 'frozen' property is changed, but its balance doesn't change
        writableTokenRelStore.put(tokenRel.copyBuilder().frozen(true).build());
        context = mockContext();

        subject.handle(context);

        BDDMockito.verifyNoInteractions(recordBuilder);
    }

    @Test
    void handleFungibleTransfersToNewAccountSuccess() {
        // This case handles a successful fungible token transfer to an auto-created account

        final var senderAcct = ACCOUNT_1212;
        final var senderTokenRel = givenFungibleTokenRelation()
                .copyBuilder()
                .tokenId(TOKEN_321)
                .accountId(ACCOUNT_1212_ID)
                .build();
        final var fungibleAmountToTransfer = senderTokenRel.balance() - 1;
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(senderAcct);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(senderAcct);
        readableTokenRelStore = TestStoreFactory.newReadableStoreWithTokenRels(senderTokenRel);
        writableTokenRelStore = TestStoreFactory.newWritableStoreWithTokenRels(senderTokenRel);
        readableNftStore = TestStoreFactory.newReadableStoreWithNfts(); // Intentionally empty
        writableNftStore = TestStoreFactory.newWritableStoreWithNfts(); // Intentionally empty
        // Simulate the token receiver's account (ACCOUNT_3434) being auto-created (with an hbar balance of 0)
        writableAccountStore.put(ACCOUNT_3434
                .copyBuilder()
                .tinybarBalance(0)
                .alias(Bytes.wrap("00000000000000000002"))
                .build());
        // Simulate the receiver's token relation being auto-created (and both the sender and receiver token rel
        // balances adjusted)
        writableTokenRelStore.put(senderTokenRel.copyBuilder().balance(1).build());
        writableTokenRelStore.put(senderTokenRel
                .copyBuilder()
                .accountId(ACCOUNT_3434_ID)
                .balance(fungibleAmountToTransfer)
                .build());
        context = mockContext();

        subject.handle(context);

        BDDMockito.verify(recordBuilder)
                .tokenTransferLists(List.of(TokenTransferList.newBuilder()
                        .token(TOKEN_321)
                        .transfers(
                                AccountAmount.newBuilder()
                                        .accountID(ACCOUNT_1212_ID)
                                        .amount(-fungibleAmountToTransfer)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(ACCOUNT_3434_ID)
                                        .amount(fungibleAmountToTransfer)
                                        .build())
                        .build()));
    }

    @Test
    void handleFungibleTransfersToExistingAccountsSuccess() {
        // This test case handles successfully transferring fungible tokens only

        final var token1Id = fungibleTokenId;
        final var token2Id = asToken(2);
        final var token3Id = asToken(3);
        // Note: givenFungibleTokenRelation() has a non-zero balance, so we don't need to modify the token rel balances
        final var acct1212Token1Rel = givenFungibleTokenRelation()
                .copyBuilder()
                .accountId(ACCOUNT_1212_ID)
                .build();
        final var acct3434Token1Rel = givenFungibleTokenRelation()
                .copyBuilder()
                .tokenId(token1Id)
                .accountId(ACCOUNT_3434_ID)
                .balance(0)
                .build();
        final var acct3434Token2Rel = givenFungibleTokenRelation()
                .copyBuilder()
                .tokenId(token2Id)
                .accountId(ACCOUNT_3434_ID)
                .build();
        final var acct5656Token2Rel = givenFungibleTokenRelation()
                .copyBuilder()
                .tokenId(token2Id)
                .accountId(ACCOUNT_5656_ID)
                .balance(0)
                .build();
        final var acct5656Token3Rel = givenFungibleTokenRelation()
                .copyBuilder()
                .tokenId(token3Id)
                .accountId(ACCOUNT_5656_ID)
                .build();
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434, ACCOUNT_5656);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434, ACCOUNT_5656);
        readableTokenRelStore = TestStoreFactory.newReadableStoreWithTokenRels(
                acct1212Token1Rel, acct3434Token1Rel, acct3434Token2Rel, acct5656Token2Rel, acct5656Token3Rel);
        writableTokenRelStore = TestStoreFactory.newWritableStoreWithTokenRels(
                acct1212Token1Rel, acct3434Token1Rel, acct3434Token2Rel, acct5656Token2Rel, acct5656Token3Rel);
        readableNftStore = TestStoreFactory.newReadableStoreWithNfts(); // Intentionally empty
        writableNftStore = TestStoreFactory.newWritableStoreWithNfts(); // Intentionally empty
        // The account in tokenRel1 will send X fungible units of token 1 to the account on tokenRel2
        // The account in tokenRel2 will send Y fungible units of token 2 to the account on tokenRel3
        // Token rels 1 and 2 will have balance changes, but token rel 3's balance won't change
        final var token1AmountTransferred = acct1212Token1Rel.balance() - 1;
        writableTokenRelStore.put(acct1212Token1Rel.copyBuilder().balance(1).build());
        writableTokenRelStore.put(acct3434Token2Rel
                .copyBuilder()
                .tokenId(token1Id)
                .balance(token1AmountTransferred)
                .build());
        final var token2AmountTransferred = acct3434Token2Rel.balance() - 10;
        writableTokenRelStore.put(acct3434Token2Rel.copyBuilder().balance(10).build());
        writableTokenRelStore.put(acct5656Token3Rel
                .copyBuilder()
                .tokenId(token2Id)
                .balance(token2AmountTransferred)
                .build());
        context = mockContext();

        subject.handle(context);

        BDDMockito.verify(recordBuilder)
                .tokenTransferLists(List.of(
                        TokenTransferList.newBuilder()
                                .token(token1Id)
                                .transfers(
                                        AccountAmount.newBuilder()
                                                .accountID(ACCOUNT_1212_ID)
                                                .amount(-token1AmountTransferred)
                                                .isApproval(false)
                                                .build(),
                                        AccountAmount.newBuilder()
                                                .accountID(ACCOUNT_3434_ID)
                                                .amount(token1AmountTransferred)
                                                .isApproval(false)
                                                .build())
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(token2Id)
                                .transfers(
                                        AccountAmount.newBuilder()
                                                .accountID(ACCOUNT_3434_ID)
                                                .amount(-token2AmountTransferred)
                                                .isApproval(false)
                                                .build(),
                                        AccountAmount.newBuilder()
                                                .accountID(ACCOUNT_5656_ID)
                                                .amount(token2AmountTransferred)
                                                .isApproval(false)
                                                .build())
                                .build()));
    }

    @Test
    void handleNftTransfersToNewAccountSuccess() {
        // This case handles a successful NFT transfer to an auto-created account
        final var existingTokenRel = givenNonFungibleTokenRelation()
                .copyBuilder()
                .tokenId(TOKEN_321)
                .accountId(ACCOUNT_1212_ID)
                .build();
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(ACCOUNT_1212);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(ACCOUNT_1212);
        readableTokenRelStore = TestStoreFactory.newReadableStoreWithTokenRels(existingTokenRel);
        writableTokenRelStore = TestStoreFactory.newWritableStoreWithTokenRels(existingTokenRel);
        final var nft = givenNft(
                        NftID.newBuilder().tokenId(TOKEN_321).serialNumber(1).build())
                .copyBuilder()
                .ownerId(ACCOUNT_1212_ID)
                .build();
        readableNftStore = TestStoreFactory.newReadableStoreWithNfts(nft);
        writableNftStore = TestStoreFactory.newWritableStoreWithNfts(nft);
        // Simulate the token receiver's account (ACCOUNT_3434) being auto-created
        writableAccountStore.put(ACCOUNT_3434
                .copyBuilder()
                .tinybarBalance(0)
                .alias(Bytes.wrap("00000000000000000003"))
                .build());
        writableNftStore.put(nft.copyBuilder().ownerId(ACCOUNT_3434_ID).build());
        context = mockContext();

        subject.handle(context);

        BDDMockito.verify(recordBuilder)
                .tokenTransferLists(List.of(TokenTransferList.newBuilder()
                        .token(TOKEN_321)
                        .nftTransfers(NftTransfer.newBuilder()
                                .serialNumber(1)
                                .senderAccountID(ACCOUNT_1212_ID)
                                .receiverAccountID(ACCOUNT_3434_ID)
                                .build())
                        .build()));
    }

    @Test
    void handleNewNftTransferToAccountSuccess() {
        final var existingTokenRel = givenNonFungibleTokenRelation()
                .copyBuilder()
                .tokenId(TOKEN_321)
                .accountId(ACCOUNT_1212_ID)
                .build();
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434);
        readableTokenRelStore = TestStoreFactory.newReadableStoreWithTokenRels(existingTokenRel);
        writableTokenRelStore = TestStoreFactory.newWritableStoreWithTokenRels(existingTokenRel);
        readableNftStore = TestStoreFactory.newReadableStoreWithNfts(); // Intentionally empty
        writableNftStore = TestStoreFactory.newWritableStoreWithNfts(); // Intentionally empty
        // Simulate the NFT being created and transferred to the receiver's account (ACCOUNT_3434)
        final var newNft = givenNft(
                        NftID.newBuilder().tokenId(TOKEN_321).serialNumber(1).build())
                .copyBuilder()
                .ownerId(ACCOUNT_1212_ID)
                .build();
        writableNftStore.put(newNft.copyBuilder().ownerId(ACCOUNT_3434_ID).build());
        context = mockContext();

        subject.handle(context);

        BDDMockito.verify(recordBuilder)
                .tokenTransferLists(List.of(TokenTransferList.newBuilder()
                        .token(TOKEN_321)
                        .nftTransfers(NftTransfer.newBuilder()
                                .serialNumber(1)
                                .senderAccountID((AccountID) null)
                                .receiverAccountID(ACCOUNT_3434_ID)
                                .build())
                        .build()));
    }

    @Test
    void handleNftTransfersToExistingAccountSuccess() {
        // This test case handles successfully transferring NFTs only

        // Set up NFTs for token ID 531 (serials 111, 112)
        final var nftId111 =
                NftID.newBuilder().tokenId(TOKEN_321).serialNumber(111).build();
        final var nft111 =
                Nft.newBuilder().id(nftId111).ownerId(ACCOUNT_1212_ID).build();
        final var nft112 = nft111.copyBuilder()
                .id(nftId111.copyBuilder().serialNumber(112).build())
                .build();
        final var acct1212tokenRel1 = givenNonFungibleTokenRelation()
                .copyBuilder()
                .accountId(ACCOUNT_1212_ID)
                .build();
        final var acct3434tokenRel1 = givenNonFungibleTokenRelation()
                .copyBuilder()
                .accountId(ACCOUNT_3434_ID)
                .build();

        // Set up NFTs for token ID 246 (serials 222, 223)
        final var token246Id = asToken(246);
        final var nftId222 =
                NftID.newBuilder().tokenId(token246Id).serialNumber(222).build();
        final var nft222 =
                nft111.copyBuilder().id(nftId222).ownerId(ACCOUNT_3434_ID).build();
        final var nft223 = nft222.copyBuilder()
                .id(nftId222.copyBuilder().serialNumber(223).build())
                .build();
        final var acct1212tokenRel2 = givenNonFungibleTokenRelation()
                .copyBuilder()
                .accountId(ACCOUNT_1212_ID)
                .build();
        final var acct3434tokenRel2 = givenNonFungibleTokenRelation()
                .copyBuilder()
                .accountId(ACCOUNT_3434_ID)
                .build();

        // Set up stores
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434);
        readableTokenRelStore = TestStoreFactory.newReadableStoreWithTokenRels(
                acct1212tokenRel1, acct3434tokenRel1, acct1212tokenRel2, acct3434tokenRel2);
        writableTokenRelStore = TestStoreFactory.newWritableStoreWithTokenRels(
                acct1212tokenRel1, acct3434tokenRel1, acct1212tokenRel2, acct3434tokenRel2);
        readableNftStore = TestStoreFactory.newReadableStoreWithNfts(nft111, nft112, nft222, nft223);
        writableNftStore = TestStoreFactory.newWritableStoreWithNfts(nft111, nft112, nft222, nft223);
        writableNftStore.put(nft111.copyBuilder().ownerId(ACCOUNT_3434_ID).build());
        writableNftStore.put(nft112.copyBuilder().ownerId(ACCOUNT_3434_ID).build());
        writableNftStore.put(nft222.copyBuilder().ownerId(ACCOUNT_1212_ID).build());
        writableNftStore.put(nft223.copyBuilder().ownerId(ACCOUNT_1212_ID).build());
        context = mockContext();

        subject.handle(context);

        // The transfer list should be sorted by token ID, then by serial number
        BDDMockito.verify(recordBuilder)
                .tokenTransferLists(List.of(
                        // Expected transfer list for token246
                        TokenTransferList.newBuilder()
                                .token(token246Id)
                                .nftTransfers(
                                        NftTransfer.newBuilder()
                                                .serialNumber(222)
                                                .senderAccountID(ACCOUNT_3434_ID)
                                                .receiverAccountID(ACCOUNT_1212_ID)
                                                .build(),
                                        NftTransfer.newBuilder()
                                                .serialNumber(223)
                                                .senderAccountID(ACCOUNT_3434_ID)
                                                .receiverAccountID(ACCOUNT_1212_ID)
                                                .build())
                                .build(),
                        // Expected transfer list for TOKEN_531
                        TokenTransferList.newBuilder()
                                .token(TOKEN_321)
                                .nftTransfers(
                                        NftTransfer.newBuilder()
                                                .serialNumber(111)
                                                .senderAccountID(ACCOUNT_1212_ID)
                                                .receiverAccountID(ACCOUNT_3434_ID)
                                                .build(),
                                        NftTransfer.newBuilder()
                                                .serialNumber(112)
                                                .senderAccountID(ACCOUNT_1212_ID)
                                                .receiverAccountID(ACCOUNT_3434_ID)
                                                .build())
                                .build()));
    }

    @Test
    void handleCombinedHbarAndTokenTransfersSuccess() {
        // This test case tests the combined success of hbar, fungible token, and nft transfers

        final var token321Rel = givenFungibleTokenRelation()
                .copyBuilder()
                .tokenId(TOKEN_321)
                .accountId(ACCOUNT_3434_ID)
                .balance(50)
                .build();
        final var token654Id = asToken(654);
        final var token654Rel = givenNonFungibleTokenRelation()
                .copyBuilder()
                .tokenId(token654Id)
                .accountId(ACCOUNT_5656_ID)
                .build();
        readableAccountStore = TestStoreFactory.newReadableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434, ACCOUNT_5656);
        writableAccountStore = TestStoreFactory.newWritableStoreWithAccounts(ACCOUNT_1212, ACCOUNT_3434, ACCOUNT_5656);
        readableTokenRelStore = TestStoreFactory.newReadableStoreWithTokenRels(token321Rel, token654Rel);
        writableTokenRelStore = TestStoreFactory.newWritableStoreWithTokenRels(token321Rel, token654Rel);
        final var nft = givenNft(
                        NftID.newBuilder().tokenId(token654Id).serialNumber(2).build())
                .copyBuilder()
                .ownerId(ACCOUNT_5656_ID)
                .build();
        readableNftStore = TestStoreFactory.newReadableStoreWithNfts(nft);
        writableNftStore = TestStoreFactory.newWritableStoreWithNfts(nft);
        // Make hbar changes
        final var hbar1212Change = ACCOUNT_1212.tinybarBalance() - 5;
        writableAccountStore.put(ACCOUNT_1212.copyBuilder().tinybarBalance(5).build());
        writableAccountStore.put(ACCOUNT_3434
                .copyBuilder()
                .tinybarBalance(ACCOUNT_3434.tinybarBalance() + hbar1212Change)
                .build());
        // Make fungible token changes
        final var fungible321Change = token321Rel.balance() - 25;
        writableTokenRelStore.put(token321Rel.copyBuilder().balance(25).build());
        writableTokenRelStore.put(token321Rel
                .copyBuilder()
                .accountId(ACCOUNT_5656_ID)
                .balance(fungible321Change)
                .build());
        // Make NFT changes
        writableNftStore.put(nft.copyBuilder().ownerId(ACCOUNT_1212_ID).build());
        context = mockContext();

        subject.handle(context);

        BDDMockito.verify(recordBuilder)
                .transferList(TransferList.newBuilder()
                        .accountAmounts(
                                AccountAmount.newBuilder()
                                        .accountID(ACCOUNT_1212_ID)
                                        .amount(-hbar1212Change)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(ACCOUNT_3434_ID)
                                        .amount(hbar1212Change)
                                        .build())
                        .build());
        BDDMockito.verify(recordBuilder)
                .tokenTransferLists(List.of(
                        TokenTransferList.newBuilder()
                                .token(TOKEN_321)
                                .transfers(
                                        AccountAmount.newBuilder()
                                                .accountID(ACCOUNT_3434_ID)
                                                .amount(-fungible321Change)
                                                .build(),
                                        AccountAmount.newBuilder()
                                                .accountID(ACCOUNT_5656_ID)
                                                .amount(fungible321Change)
                                                .build())
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(token654Id)
                                .nftTransfers(NftTransfer.newBuilder()
                                        .serialNumber(2)
                                        .senderAccountID(ACCOUNT_5656_ID)
                                        .receiverAccountID(ACCOUNT_1212_ID)
                                        .build())
                                .build()));
    }

    private HandleContext mockContext() {
        final var context = mock(HandleContext.class);

        given(context.recordBuilder(CryptoTransferRecordBuilder.class)).willReturn(recordBuilder);

        given(context.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.readableStore(ReadableTokenRelationStore.class)).willReturn(readableTokenRelStore);
        given(context.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(context.readableStore(ReadableNftStore.class)).willReturn(readableNftStore);
        given(context.writableStore(WritableNftStore.class)).willReturn(writableNftStore);

        return context;
    }

    private static AccountID asAccountId(final Account acct) {
        return AccountID.newBuilder()
                .shardNum(0)
                .realmNum(0)
                .accountNum(acct.accountNumber())
                .build();
    }
}
