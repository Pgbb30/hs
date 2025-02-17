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

package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import static com.hedera.node.app.service.mono.pbj.PbjConverter.asBytes;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWith;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWithAllowance;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.nftTransferWith;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.nftTransferWithAllowance;
import static com.swirlds.common.utility.CommonUtils.unhex;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.google.common.primitives.Longs;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.mono.config.HederaNumbers;
import com.hedera.node.app.service.mono.context.properties.GlobalDynamicProperties;
import com.hedera.node.app.service.mono.context.properties.PropertySource;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.AdjustFungibleTokenChangesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.AdjustHbarChangesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.AssociateTokenRecipientsStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.EnsureAliasesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.NFTOwnersChangeStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.ReplaceAliasesWithIDsInOp;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.service.token.impl.records.CryptoCreateRecordBuilder;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.workflows.handle.record.SingleTransactionRecordBuilder;
import com.hedera.node.app.workflows.handle.validation.StandardizedAttributeValidator;
import com.hedera.node.app.workflows.handle.validation.StandardizedExpiryValidator;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class StepsBase extends CryptoTokenHandlerTestBase {
    @Mock
    private HederaNumbers hederaNumbers;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private LongSupplier consensusSecondNow;

    @Mock
    private GlobalDynamicProperties dynamicProperties;

    @Mock
    private PropertySource compositeProps;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ConfigProvider configProvider;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected HandleContext handleContext;

    private AttributeValidator attributeValidator;

    protected ExpiryValidator expiryValidator;
    protected EnsureAliasesStep ensureAliasesStep;
    protected ReplaceAliasesWithIDsInOp replaceAliasesWithIDsInOp;
    protected AssociateTokenRecipientsStep associateTokenRecepientsStep;
    protected NFTOwnersChangeStep changeNFTOwnersStep;
    protected AdjustHbarChangesStep adjustHbarChangesStep;
    protected AdjustFungibleTokenChangesStep adjustFungibleTokenChangesStep;
    protected CryptoTransferTransactionBody body;
    protected TransactionBody txn;
    protected TransferContextImpl transferContext;
    protected SingleTransactionRecordBuilder recordBuilder;

    @BeforeEach
    public void setUp() {
        super.setUp();
        recordBuilder = new SingleTransactionRecordBuilder(consensusInstant);
        attributeValidator = new StandardizedAttributeValidator(consensusSecondNow, compositeProps, dynamicProperties);
        expiryValidator = new StandardizedExpiryValidator(
                System.out::println, attributeValidator, consensusSecondNow, hederaNumbers, configProvider);
        refreshWritableStores();
    }

    protected final AccountID unknownAliasedId =
            AccountID.newBuilder().alias(ecKeyAlias).build();
    protected final AccountID unknownAliasedId1 =
            AccountID.newBuilder().alias(edKeyAlias).build();

    protected static final Key aPrimitiveKey = Key.newBuilder()
            .ed25519(Bytes.wrap("01234567890123456789012345678911"))
            .build();
    protected static final Bytes edKeyAlias = Bytes.wrap(asBytes(Key.PROTOBUF, aPrimitiveKey));
    protected static final byte[] ecdsaKeyBytes =
            Hex.decode("3a21033a514176466fa815ed481ffad09110a2d344f6c9b78c1d14afc351c3a51be33d");
    protected static final Bytes ecKeyAlias = Bytes.wrap(ecdsaKeyBytes);

    protected static final byte[] evmAddress = unhex("0000000000000000000000000000000000000003");
    protected static final byte[] create2Address = unhex("0111111111111111111111111111111111defbbb");
    protected static final Bytes mirrorAlias = Bytes.wrap(evmAddress);
    protected static final Bytes create2Alias = Bytes.wrap(create2Address);
    protected static final Long mirrorNum = Longs.fromByteArray(Arrays.copyOfRange(evmAddress, 12, 20));
    protected final int hbarReceiver = 10000000;
    protected final int tokenReceiver = hbarReceiver + 1;

    protected TransactionBody asTxn(final CryptoTransferTransactionBody body, final AccountID payerId) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(payerId)
                        .transactionValidStart(consensusTimestamp)
                        .build())
                .cryptoTransfer(body)
                .build();
    }

    protected void givenTxn() {
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWith(ownerId, -1_000), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .token(fungibleTokenId)
                                .expectedDecimals(1000)
                                .transfers(List.of(aaWith(ownerId, -1_000), aaWith(unknownAliasedId1, +1_000)))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(nonFungibleTokenId)
                                .expectedDecimals(1000)
                                .nftTransfers(nftTransferWith(ownerId, unknownAliasedId1, 1))
                                .build())
                .build();
        givenTxn(body, payerId);
    }

    protected void givenTxnWithAllowances() {
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWithAllowance(ownerId, -1_000), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .expectedDecimals(1000)
                                .token(fungibleTokenId)
                                .transfers(List.of(aaWithAllowance(ownerId, -1_000), aaWith(unknownAliasedId1, +1_000)))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(nonFungibleTokenId)
                                .nftTransfers(nftTransferWithAllowance(ownerId, unknownAliasedId1, 1))
                                .build())
                .build();
        givenTxn(body, spenderId);
    }

    protected void givenTxn(CryptoTransferTransactionBody txnBody, AccountID payerId) {
        body = txnBody;
        txn = asTxn(body, payerId);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.dispatchRemovableChildTransaction(any(), eq(CryptoCreateRecordBuilder.class)))
                .willReturn(recordBuilder);
        transferContext = new TransferContextImpl(handleContext);
        given(configProvider.getConfiguration()).willReturn(versionedConfig);
        //        given(handleContext.feeCalculator()).willReturn(fees);
        //        given(fees.computePayment(any(), any())).willReturn(new FeeObject(100, 100, 100));
    }

    protected void givenAutoCreationDispatchEffects() {
        given(handleContext.dispatchRemovableChildTransaction(any(), eq(CryptoCreateRecordBuilder.class)))
                .will((invocation) -> {
                    final var copy = account.copyBuilder()
                            .alias(ecKeyAlias)
                            .accountNumber(hbarReceiver)
                            .build();
                    writableAccountStore.put(copy);
                    writableAliases.put(ecKeyAlias, asAccount(hbarReceiver));
                    return recordBuilder.accountID(asAccount(hbarReceiver));
                })
                .will((invocation) -> {
                    final var copy = account.copyBuilder()
                            .alias(edKeyAlias)
                            .accountNumber(tokenReceiver)
                            .build();
                    writableAccountStore.put(copy);
                    writableAliases.put(edKeyAlias, asAccount(tokenReceiver));
                    return recordBuilder.accountID(asAccount(tokenReceiver));
                });
        given(handleContext.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
    }
}
