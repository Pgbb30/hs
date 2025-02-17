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

package com.hedera.node.app.workflows.prehandle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNKNOWN;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.NODE_DUE_DILIGENCE_FAILURE;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.PRE_HANDLE_FAILURE;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.SO_FAR_SO_GOOD;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.UNKNOWN_FAILURE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.workflows.TransactionInfo;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class PreHandleResultTest implements Scenarios {

    private static final long DEFAULT_CONFIG_VERSION = 1L;

    /**
     * Tests to verify the creation of the object. Simple null checks, and verifying that the different static
     * construction methods all creation proper objects.
     */
    @Nested
    @DisplayName("Testing Creation of PreHandleResult")
    @ExtendWith(MockitoExtension.class)
    final class CreationTests {
        /** The {@link PreHandleResult#status()} must not be null. */
        @Test
        @DisplayName("The status must not be null")
        @SuppressWarnings("ConstantConditions")
        void statusMustNotBeNull(
                @Mock AccountID payer, @Mock TransactionInfo txInfo, @Mock PreHandleResult innerResult) {
            assertThatThrownBy(() -> new PreHandleResult(
                            payer,
                            Key.DEFAULT,
                            null,
                            OK,
                            txInfo,
                            Set.of(),
                            Map.of(),
                            innerResult,
                            DEFAULT_CONFIG_VERSION))
                    .isInstanceOf(NullPointerException.class);
        }

        /** The {@link PreHandleResult#responseCode()} must not be null. */
        @Test
        @DisplayName("The response code must not be null")
        @SuppressWarnings("ConstantConditions")
        void responseCodeMustNotBeNull(
                @Mock AccountID payer, @Mock TransactionInfo txInfo, @Mock PreHandleResult innerResult) {
            assertThatThrownBy(() -> new PreHandleResult(
                            payer,
                            Key.DEFAULT,
                            SO_FAR_SO_GOOD,
                            null,
                            txInfo,
                            Set.of(),
                            Map.of(),
                            innerResult,
                            DEFAULT_CONFIG_VERSION))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Unknown failures set the status and response code and everything else is null")
        void unknownFailure() {
            final var result = PreHandleResult.unknownFailure();

            assertThat(result.status()).isEqualTo(UNKNOWN_FAILURE);
            assertThat(result.responseCode()).isEqualTo(UNKNOWN);
            assertThat(result.innerResult()).isNull();
            assertThat(result.payer()).isNull();
            assertThat(result.txInfo()).isNull();
            assertThat(result.requiredKeys()).isNull();
            assertThat(result.verificationResults()).isNull();
        }

        @Test
        @DisplayName(
                "Node Diligence Failures only set the status and response code and the payer to be the node and the tx info")
        void nodeDiligenceFailure(@Mock TransactionInfo txInfo) {
            final var nodeAccountId = AccountID.newBuilder().accountNum(3).build();
            final var status = INVALID_PAYER_ACCOUNT_ID;
            final var result = PreHandleResult.nodeDueDiligenceFailure(nodeAccountId, status, txInfo);

            assertThat(result.status()).isEqualTo(NODE_DUE_DILIGENCE_FAILURE);
            assertThat(result.responseCode()).isEqualTo(status);
            assertThat(result.innerResult()).isNull();
            assertThat(result.payer()).isEqualTo(nodeAccountId);
            assertThat(result.txInfo()).isSameAs(txInfo);
            assertThat(result.requiredKeys()).isNull();
            assertThat(result.verificationResults()).isNull();
        }

        @Test
        @DisplayName("Pre-Handle Failures set the payer, status, responseCode, and txInfo")
        void preHandleFailure(@Mock TransactionInfo txInfo) {
            final var payer = AccountID.newBuilder().accountNum(1001).build();
            final var responseCode = INVALID_PAYER_ACCOUNT_ID;
            final var result = PreHandleResult.preHandleFailure(payer, null, responseCode, txInfo, null, null);

            assertThat(result.status()).isEqualTo(PRE_HANDLE_FAILURE);
            assertThat(result.responseCode()).isEqualTo(responseCode);
            assertThat(result.innerResult()).isNull();
            assertThat(result.payer()).isEqualTo(payer);
            assertThat(result.txInfo()).isSameAs(txInfo);
            assertThat(result.requiredKeys()).isNull();
            assertThat(result.verificationResults()).isNull();
        }
    }
}
