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

package com.hedera.node.app.workflows.handle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.AppTestBase;
import com.hedera.node.app.config.VersionedConfigImpl;
import com.hedera.node.app.fixtures.signature.ExpandedSignaturePairFactory;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.services.ServiceScopeLookup;
import com.hedera.node.app.signature.SignatureExpander;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.signature.SignatureVerifier;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.TransactionScenarioBuilder;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.prehandle.FakeSignatureVerificationFuture;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.node.app.workflows.prehandle.PreHandleResult.Status;
import com.hedera.node.app.workflows.prehandle.PreHandleWorkflow;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.common.system.NodeId;
import com.swirlds.common.system.Round;
import com.swirlds.common.system.transaction.internal.SwirldTransaction;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleWorkflowTest extends AppTestBase {

    private static final Instant CONSENSUS_NOW = Instant.parse("2000-01-01T00:00:00Z");

    private static final long CONFIG_VERSION = 11L;

    private static final PreHandleResult OK_RESULT = createPreHandleResult(Status.SO_FAR_SO_GOOD, ResponseCodeEnum.OK);

    private static final PreHandleResult PRE_HANDLE_FAILURE_RESULT =
            createPreHandleResult(Status.PRE_HANDLE_FAILURE, ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID);

    private static final PreHandleResult DUE_DILIGENCE_RESULT = PreHandleResult.nodeDueDiligenceFailure(
            NODE_1.nodeAccountID(), ResponseCodeEnum.INVALID_TRANSACTION, new TransactionScenarioBuilder().txInfo());

    private static PreHandleResult createPreHandleResult(@NonNull Status status, @NonNull ResponseCodeEnum code) {
        final var key = ALICE.account().keyOrThrow();
        return new PreHandleResult(
                ALICE.accountID(),
                key,
                status,
                code,
                new TransactionScenarioBuilder().txInfo(),
                Set.of(),
                Map.of(key, FakeSignatureVerificationFuture.goodFuture(key)),
                null,
                CONFIG_VERSION);
    }

    @Mock
    private NetworkInfo networkInfo;

    @Mock(strictness = LENIENT)
    private PreHandleWorkflow preHandleWorkflow;

    @Mock(strictness = LENIENT)
    private TransactionDispatcher dispatcher;

    @Mock
    private BlockRecordManager blockRecordManager;

    @Mock(strictness = LENIENT)
    private SignatureExpander signatureExpander;

    @Mock
    private SignatureVerifier signatureVerifier;

    @Mock
    private TransactionChecker checker;

    @Mock(strictness = LENIENT)
    private ServiceScopeLookup serviceLookup;

    @Mock(strictness = LENIENT)
    private ConfigProvider configProvider;

    @Mock(strictness = LENIENT)
    private Round round;

    @Mock
    private EventImpl event;

    @Mock(strictness = LENIENT)
    private SwirldTransaction platformTxn;

    private HandleWorkflow workflow;

    @BeforeEach
    void setup(@Mock InstantSource instantSource) {
        setupStandardStates();

        when(platformTxn.getConsensusTimestamp()).thenReturn(CONSENSUS_NOW);
        when(platformTxn.getMetadata()).thenReturn(OK_RESULT);

        doAnswer(invocation -> {
                    final var consumer = invocation.getArgument(0, BiConsumer.class);
                    //noinspection unchecked
                    consumer.accept(event, platformTxn);
                    return null;
                })
                .when(round)
                .forEachEventTransaction(any());

        when(serviceLookup.getServiceName(any())).thenReturn(TokenService.NAME);

        final var config = new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), CONFIG_VERSION);
        when(configProvider.getConfiguration()).thenReturn(config);

        doAnswer(invocation -> {
                    final var context = invocation.getArgument(0, HandleContext.class);
                    context.writableStore(WritableAccountStore.class)
                            .putAlias(ALICE_ALIAS, ALICE.accountID().accountNumOrThrow());
                    return null;
                })
                .when(dispatcher)
                .dispatchHandle(any());

        workflow = new HandleWorkflow(
                networkInfo,
                preHandleWorkflow,
                dispatcher,
                blockRecordManager,
                signatureExpander,
                signatureVerifier,
                checker,
                serviceLookup,
                configProvider,
                instantSource);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testContructorWithInvalidArguments() {
        final var instantSource = InstantSource.system();
        assertThatThrownBy(() -> new HandleWorkflow(
                        null,
                        preHandleWorkflow,
                        dispatcher,
                        blockRecordManager,
                        signatureExpander,
                        signatureVerifier,
                        checker,
                        serviceLookup,
                        configProvider,
                        instantSource))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HandleWorkflow(
                        networkInfo,
                        null,
                        dispatcher,
                        blockRecordManager,
                        signatureExpander,
                        signatureVerifier,
                        checker,
                        serviceLookup,
                        configProvider,
                        instantSource))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HandleWorkflow(
                        networkInfo,
                        preHandleWorkflow,
                        null,
                        blockRecordManager,
                        signatureExpander,
                        signatureVerifier,
                        checker,
                        serviceLookup,
                        configProvider,
                        instantSource))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HandleWorkflow(
                        networkInfo,
                        preHandleWorkflow,
                        dispatcher,
                        null,
                        signatureExpander,
                        signatureVerifier,
                        checker,
                        serviceLookup,
                        configProvider,
                        instantSource))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HandleWorkflow(
                        networkInfo,
                        preHandleWorkflow,
                        dispatcher,
                        blockRecordManager,
                        null,
                        signatureVerifier,
                        checker,
                        serviceLookup,
                        configProvider,
                        instantSource))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HandleWorkflow(
                        networkInfo,
                        preHandleWorkflow,
                        dispatcher,
                        blockRecordManager,
                        signatureExpander,
                        null,
                        checker,
                        serviceLookup,
                        configProvider,
                        instantSource))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HandleWorkflow(
                        networkInfo,
                        preHandleWorkflow,
                        dispatcher,
                        blockRecordManager,
                        signatureExpander,
                        signatureVerifier,
                        null,
                        serviceLookup,
                        configProvider,
                        instantSource))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HandleWorkflow(
                        networkInfo,
                        preHandleWorkflow,
                        dispatcher,
                        blockRecordManager,
                        signatureExpander,
                        signatureVerifier,
                        checker,
                        null,
                        configProvider,
                        instantSource))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HandleWorkflow(
                        networkInfo,
                        preHandleWorkflow,
                        dispatcher,
                        blockRecordManager,
                        signatureExpander,
                        signatureVerifier,
                        checker,
                        serviceLookup,
                        null,
                        instantSource))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HandleWorkflow(
                        networkInfo,
                        preHandleWorkflow,
                        dispatcher,
                        blockRecordManager,
                        signatureExpander,
                        signatureVerifier,
                        checker,
                        serviceLookup,
                        configProvider,
                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("System transaction is skipped")
    void testPlatformTxnIsSkipped() {
        // given
        when(platformTxn.isSystem()).thenReturn(true);

        // when
        workflow.handleRound(state, round);

        // then
        assertThat(accountsState.isModified()).isFalse();
        assertThat(aliasesState.isModified()).isFalse();
        verify(blockRecordManager, never()).startUserTransaction(any(), any());
        verify(blockRecordManager, never()).endUserTransaction(any(), any());
    }

    @Test
    @DisplayName("Successful execution of simple case")
    void testHappyPath() {
        // when
        workflow.handleRound(state, round);

        // then
        final var alice = aliasesState.get(ALICE_ALIAS);
        assertThat(alice).isNotNull();
        assertThat(alice.accountNum()).isEqualTo(ALICE.account().accountNumber());
        // TODO: Check that record was created
    }

    @Nested
    @DisplayName("Tests for cases when preHandle needs to be run")
    final class FullPreHandleRunTest {

        @BeforeEach
        void setup() {
            when(preHandleWorkflow.preHandleTransaction(any(), any(), any(), eq(platformTxn)))
                    .thenReturn(OK_RESULT);
        }

        @Test
        @DisplayName("Run preHandle, if it was not executed before (platformTxn.metadata is null)")
        void testPreHandleNotExecuted() {
            // given
            when(platformTxn.getMetadata()).thenReturn(null);
            when(event.getCreatorId()).thenReturn(new NodeId(0));

            // when
            workflow.handleRound(state, round);

            // then
            verify(preHandleWorkflow).preHandleTransaction(any(), any(), any(), eq(platformTxn));
        }

        @Test
        @DisplayName("Run preHandle, if previous execution resulted in Status.PRE_HANDLE_FAILURE")
        void testPreHandleFailure() {
            // given
            when(platformTxn.getMetadata()).thenReturn(PRE_HANDLE_FAILURE_RESULT);
            when(event.getCreatorId()).thenReturn(new NodeId(0));

            // when
            workflow.handleRound(state, round);

            // then
            verify(preHandleWorkflow).preHandleTransaction(any(), any(), any(), eq(platformTxn));
        }

        @Test
        @DisplayName("Run preHandle, if previous execution resulted in Status.UNKNOWN_FAILURE")
        void testUnknownFailure() {
            // given
            when(platformTxn.getMetadata()).thenReturn(PreHandleResult.unknownFailure());
            when(event.getCreatorId()).thenReturn(new NodeId(0));

            // when
            workflow.handleRound(state, round);

            // then
            verify(preHandleWorkflow).preHandleTransaction(any(), any(), any(), eq(platformTxn));
        }

        @Test
        @DisplayName("Run preHandle, if configuration changed")
        void testConfigurationChanged() {
            // given
            final var key = ALICE.account().keyOrThrow();
            final var preHandleResult = new PreHandleResult(
                    ALICE.accountID(),
                    key,
                    Status.SO_FAR_SO_GOOD,
                    ResponseCodeEnum.OK,
                    new TransactionScenarioBuilder().txInfo(),
                    Set.of(),
                    Map.of(key, FakeSignatureVerificationFuture.goodFuture(key)),
                    null,
                    CONFIG_VERSION - 1L);
            when(platformTxn.getMetadata()).thenReturn(preHandleResult);
            when(event.getCreatorId()).thenReturn(new NodeId(0));

            // when
            workflow.handleRound(state, round);

            // then
            verify(preHandleWorkflow).preHandleTransaction(any(), any(), any(), eq(platformTxn));
        }

        @Test
        @DisplayName("Handle transaction successfully, if running preHandle caused no issues")
        void testPreHandleSuccess() {
            // given
            when(platformTxn.getMetadata()).thenReturn(null);
            when(event.getCreatorId()).thenReturn(new NodeId(0));

            // when
            workflow.handleRound(state, round);

            // then
            final var alice = aliasesState.get(ALICE_ALIAS);
            assertThat(alice).isNotNull();
            assertThat(alice.accountNum()).isEqualTo(ALICE.account().accountNumber());
            // TODO: Check that record was created
        }

        @Test
        @DisplayName("Create penalty payment, if running preHandle causes a due diligence error")
        void testPreHandleCausesDueDilligenceError() {
            // given
            when(platformTxn.getMetadata()).thenReturn(null);
            when(preHandleWorkflow.preHandleTransaction(any(), any(), any(), eq(platformTxn)))
                    .thenReturn(DUE_DILIGENCE_RESULT);

            // when
            workflow.handleRound(state, round);

            // then
            assertThat(aliasesState.isModified()).isFalse();
            // TODO: Verify that we created a penalty payment (https://github.com/hashgraph/hedera-services/issues/6811)
        }

        @Test
        @DisplayName("Charge user, but do not change state otherwise, if running preHandle causes PRE_HANDLE_FAILURE")
        void testPreHandleCausesPreHandleFailure() {
            // given
            when(platformTxn.getMetadata()).thenReturn(null);
            when(preHandleWorkflow.preHandleTransaction(any(), any(), any(), eq(platformTxn)))
                    .thenReturn(DUE_DILIGENCE_RESULT);

            // when
            workflow.handleRound(state, round);

            // then
            assertThat(aliasesState.isModified()).isFalse();
            // TODO: Check that record was created
        }

        @Test
        @DisplayName("Update receipt, but charge no one, if running preHandle causes Status.UNKNOWN_FAILURE")
        void testPreHandleCausesUnknownFailure() {
            when(platformTxn.getMetadata()).thenReturn(null);
            when(preHandleWorkflow.preHandleTransaction(any(), any(), any(), eq(platformTxn)))
                    .thenReturn(PreHandleResult.unknownFailure());

            // when
            workflow.handleRound(state, round);

            // then
            assertThat(accountsState.isModified()).isFalse();
            assertThat(aliasesState.isModified()).isFalse();
            // TODO: Check receipt
        }
    }

    @Test
    @DisplayName("Create penalty payment, if  previous preHandle resulted in a due diligence error")
    void testPreHandleWithDueDiligenceFailure() {
        // given
        when(platformTxn.getMetadata()).thenReturn(DUE_DILIGENCE_RESULT);
        // when
        workflow.handleRound(state, round);

        // then
        assertThat(aliasesState.isModified()).isFalse();
        // TODO: Verify that we created a penalty payment (https://github.com/hashgraph/hedera-services/issues/6811)
    }

    @Nested
    @DisplayName("Tests for cases when preHandle ran successfully")
    final class AddMissingSignaturesTest {

        @Test
        @DisplayName("Add passing verification result, if a key was handled in preHandle")
        void testRequiredExistingKeyWithPassingSignature() throws PreCheckException, TimeoutException {
            // given
            final var alicesKey = ALICE.account().keyOrThrow();
            final var bobsKey = BOB.account().keyOrThrow();
            final var verificationResults = Map.<Key, SignatureVerificationFuture>of(
                    alicesKey, FakeSignatureVerificationFuture.goodFuture(alicesKey),
                    bobsKey, FakeSignatureVerificationFuture.goodFuture(bobsKey));
            final var preHandleResult = new PreHandleResult(
                    ALICE.accountID(),
                    alicesKey,
                    Status.SO_FAR_SO_GOOD,
                    ResponseCodeEnum.OK,
                    new TransactionScenarioBuilder().txInfo(),
                    Set.of(bobsKey),
                    verificationResults,
                    null,
                    CONFIG_VERSION);
            when(platformTxn.getMetadata()).thenReturn(preHandleResult);
            doAnswer(invocation -> {
                        final var context = invocation.getArgument(0, PreHandleContext.class);
                        context.requireKey(bobsKey);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());
            doAnswer(invocation -> {
                        final var expanded = invocation.getArgument(2, Set.class);
                        expanded.add(ExpandedSignaturePairFactory.ed25519Pair(bobsKey));
                        return null;
                    })
                    .when(signatureExpander)
                    .expand(eq(Set.of(bobsKey)), any(), any());

            // when
            workflow.handleRound(state, round);

            // then
            final var argCapture = ArgumentCaptor.forClass(HandleContext.class);
            verify(dispatcher).dispatchHandle(argCapture.capture());
            final var alicesVerification = argCapture.getValue().verificationFor(alicesKey);
            assertThat(alicesVerification).isNotNull();
            assertThat(alicesVerification.key()).isEqualTo(alicesKey);
            assertThat(alicesVerification.evmAlias()).isNull();
            assertThat(alicesVerification.passed()).isTrue();
            final var bobsVerification = argCapture.getValue().verificationFor(bobsKey);
            assertThat(bobsVerification).isNotNull();
            assertThat(bobsVerification.key()).isEqualTo(bobsKey);
            assertThat(bobsVerification.evmAlias()).isNull();
            assertThat(bobsVerification.passed()).isTrue();
        }

        @Test
        @DisplayName("Add failing verification result, if a key was handled in preHandle")
        void testRequiredExistingKeyWithFailingSignature() throws PreCheckException {
            // given
            final var alicesKey = ALICE.account().keyOrThrow();
            final var bobsKey = BOB.account().keyOrThrow();
            final var verificationResults = Map.<Key, SignatureVerificationFuture>of(
                    alicesKey, FakeSignatureVerificationFuture.goodFuture(alicesKey),
                    bobsKey, FakeSignatureVerificationFuture.badFuture(bobsKey));
            final var preHandleResult = new PreHandleResult(
                    ALICE.accountID(),
                    alicesKey,
                    Status.SO_FAR_SO_GOOD,
                    ResponseCodeEnum.OK,
                    new TransactionScenarioBuilder().txInfo(),
                    Set.of(bobsKey),
                    verificationResults,
                    null,
                    CONFIG_VERSION);
            when(platformTxn.getMetadata()).thenReturn(preHandleResult);
            doAnswer(invocation -> {
                        final var context = invocation.getArgument(0, PreHandleContext.class);
                        context.requireKey(bobsKey);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());
            doAnswer(invocation -> {
                        final var expanded = invocation.getArgument(2, Set.class);
                        expanded.add(ExpandedSignaturePairFactory.ed25519Pair(bobsKey));
                        return null;
                    })
                    .when(signatureExpander)
                    .expand(eq(Set.of(bobsKey)), any(), any());

            // when
            workflow.handleRound(state, round);

            // then
            verify(dispatcher, never()).dispatchHandle(any());
        }

        @Test
        @DisplayName("Trigger passing verification, if new key was found")
        void testRequiredNewKeyWithPassingSignature() throws PreCheckException, TimeoutException {
            // given
            final var alicesKey = ALICE.account().keyOrThrow();
            final var bobsKey = BOB.account().keyOrThrow();
            final var verificationResults = Map.<Key, SignatureVerificationFuture>of(
                    bobsKey, FakeSignatureVerificationFuture.goodFuture(bobsKey));
            doAnswer(invocation -> {
                        final var context = invocation.getArgument(0, PreHandleContext.class);
                        context.requireKey(bobsKey);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());
            doAnswer(invocation -> {
                        final var expanded = invocation.getArgument(2, Set.class);
                        expanded.add(ExpandedSignaturePairFactory.ed25519Pair(bobsKey));
                        return null;
                    })
                    .when(signatureExpander)
                    .expand(eq(Set.of(bobsKey)), any(), any());
            when(signatureVerifier.verify(
                            any(),
                            argThat(set -> set.size() == 1
                                    && bobsKey.equals(set.iterator().next().key()))))
                    .thenReturn(verificationResults);

            // when
            workflow.handleRound(state, round);

            // then
            final var argCapture = ArgumentCaptor.forClass(HandleContext.class);
            verify(dispatcher).dispatchHandle(argCapture.capture());
            final var alicesVerification = argCapture.getValue().verificationFor(alicesKey);
            assertThat(alicesVerification).isNotNull();
            assertThat(alicesVerification.key()).isEqualTo(alicesKey);
            assertThat(alicesVerification.evmAlias()).isNull();
            assertThat(alicesVerification.passed()).isTrue();
            final var bobsVerification = argCapture.getValue().verificationFor(bobsKey);
            assertThat(bobsVerification).isNotNull();
            assertThat(bobsVerification.key()).isEqualTo(bobsKey);
            assertThat(bobsVerification.evmAlias()).isNull();
            assertThat(bobsVerification.passed()).isTrue();
        }

        @Test
        @DisplayName("Trigger failing verification, if new key was found")
        void testRequiredNewKeyWithFailingSignature() throws PreCheckException {
            // given
            final var bobsKey = BOB.account().keyOrThrow();
            doAnswer(invocation -> {
                        final var context = invocation.getArgument(0, PreHandleContext.class);
                        context.requireKey(bobsKey);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());
            doAnswer(invocation -> {
                        final var expanded = invocation.getArgument(2, Set.class);
                        expanded.add(ExpandedSignaturePairFactory.ed25519Pair(bobsKey));
                        return null;
                    })
                    .when(signatureExpander)
                    .expand(eq(Set.of(bobsKey)), any(), any());
            final var verificationResults = Map.<Key, SignatureVerificationFuture>of(
                    bobsKey, FakeSignatureVerificationFuture.badFuture(bobsKey));
            when(signatureVerifier.verify(
                            any(),
                            argThat(set -> set.size() == 1
                                    && bobsKey.equals(set.iterator().next().key()))))
                    .thenReturn(verificationResults);

            // when
            workflow.handleRound(state, round);

            // then
            verify(dispatcher, never()).dispatchHandle(any());
        }

        @Test
        @DisplayName("Add passing verification result, if a key was handled in preHandle")
        void testOptionalExistingKeyWithPassingSignature() throws PreCheckException, TimeoutException {
            // given
            final var alicesKey = ALICE.account().keyOrThrow();
            final var bobsKey = BOB.account().keyOrThrow();
            final var verificationResults = Map.<Key, SignatureVerificationFuture>of(
                    alicesKey, FakeSignatureVerificationFuture.goodFuture(alicesKey),
                    bobsKey, FakeSignatureVerificationFuture.goodFuture(bobsKey));
            final var preHandleResult = new PreHandleResult(
                    ALICE.accountID(),
                    alicesKey,
                    Status.SO_FAR_SO_GOOD,
                    ResponseCodeEnum.OK,
                    new TransactionScenarioBuilder().txInfo(),
                    Set.of(),
                    verificationResults,
                    null,
                    CONFIG_VERSION);
            when(platformTxn.getMetadata()).thenReturn(preHandleResult);
            doAnswer(invocation -> {
                        final var context = invocation.getArgument(0, PreHandleContext.class);
                        context.optionalKey(bobsKey);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());
            doAnswer(invocation -> {
                        final var expanded = invocation.getArgument(2, Set.class);
                        expanded.add(ExpandedSignaturePairFactory.ed25519Pair(bobsKey));
                        return null;
                    })
                    .when(signatureExpander)
                    .expand(eq(Set.of(bobsKey)), any(), any());

            // when
            workflow.handleRound(state, round);

            // then
            final var argCapture = ArgumentCaptor.forClass(HandleContext.class);
            verify(dispatcher).dispatchHandle(argCapture.capture());
            final var alicesVerification = argCapture.getValue().verificationFor(alicesKey);
            assertThat(alicesVerification).isNotNull();
            assertThat(alicesVerification.key()).isEqualTo(alicesKey);
            assertThat(alicesVerification.evmAlias()).isNull();
            assertThat(alicesVerification.passed()).isTrue();
            final var bobsVerification = argCapture.getValue().verificationFor(bobsKey);
            assertThat(bobsVerification).isNotNull();
            assertThat(bobsVerification.key()).isEqualTo(bobsKey);
            assertThat(bobsVerification.evmAlias()).isNull();
            assertThat(bobsVerification.passed()).isTrue();
        }

        @Test
        @DisplayName("Add failing verification result, if a key was handled in preHandle")
        void testOptionalExistingKeyWithFailingSignature() throws PreCheckException, TimeoutException {
            // given
            final var alicesKey = ALICE.account().keyOrThrow();
            final var bobsKey = BOB.account().keyOrThrow();
            final var verificationResults = Map.<Key, SignatureVerificationFuture>of(
                    alicesKey, FakeSignatureVerificationFuture.goodFuture(alicesKey),
                    bobsKey, FakeSignatureVerificationFuture.badFuture(bobsKey));
            final var preHandleResult = new PreHandleResult(
                    ALICE.accountID(),
                    alicesKey,
                    Status.SO_FAR_SO_GOOD,
                    ResponseCodeEnum.OK,
                    new TransactionScenarioBuilder().txInfo(),
                    Set.of(),
                    verificationResults,
                    null,
                    CONFIG_VERSION);
            when(platformTxn.getMetadata()).thenReturn(preHandleResult);
            doAnswer(invocation -> {
                        final var context = invocation.getArgument(0, PreHandleContext.class);
                        context.optionalKey(bobsKey);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());
            doAnswer(invocation -> {
                        final var expanded = invocation.getArgument(2, Set.class);
                        expanded.add(ExpandedSignaturePairFactory.ed25519Pair(bobsKey));
                        return null;
                    })
                    .when(signatureExpander)
                    .expand(eq(Set.of(bobsKey)), any(), any());

            // when
            workflow.handleRound(state, round);

            // then
            final var argCapture = ArgumentCaptor.forClass(HandleContext.class);
            verify(dispatcher).dispatchHandle(argCapture.capture());
            final var alicesVerification = argCapture.getValue().verificationFor(alicesKey);
            assertThat(alicesVerification).isNotNull();
            assertThat(alicesVerification.key()).isEqualTo(alicesKey);
            assertThat(alicesVerification.evmAlias()).isNull();
            assertThat(alicesVerification.passed()).isTrue();
            final var bobsVerification = argCapture.getValue().verificationFor(bobsKey);
            assertThat(bobsVerification).isNotNull();
            assertThat(bobsVerification.key()).isEqualTo(bobsKey);
            assertThat(bobsVerification.evmAlias()).isNull();
            assertThat(bobsVerification.passed()).isFalse();
        }

        @Test
        @DisplayName("Trigger passing verification, if new key was found")
        void testOptionalNewKeyWithPassingSignature() throws PreCheckException, TimeoutException {
            // given
            final var alicesKey = ALICE.account().keyOrThrow();
            final var bobsKey = BOB.account().keyOrThrow();
            final var verificationResults = Map.<Key, SignatureVerificationFuture>of(
                    bobsKey, FakeSignatureVerificationFuture.goodFuture(bobsKey));
            doAnswer(invocation -> {
                        final var context = invocation.getArgument(0, PreHandleContext.class);
                        context.optionalKey(bobsKey);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());
            doAnswer(invocation -> {
                        final var expanded = invocation.getArgument(2, Set.class);
                        expanded.add(ExpandedSignaturePairFactory.ed25519Pair(bobsKey));
                        return null;
                    })
                    .when(signatureExpander)
                    .expand(eq(Set.of(bobsKey)), any(), any());
            when(signatureVerifier.verify(
                            any(),
                            argThat(set -> set.size() == 1
                                    && bobsKey.equals(set.iterator().next().key()))))
                    .thenReturn(verificationResults);

            // when
            workflow.handleRound(state, round);

            // then
            final var argCapture = ArgumentCaptor.forClass(HandleContext.class);
            verify(dispatcher).dispatchHandle(argCapture.capture());
            final var alicesVerification = argCapture.getValue().verificationFor(alicesKey);
            assertThat(alicesVerification).isNotNull();
            assertThat(alicesVerification.key()).isEqualTo(alicesKey);
            assertThat(alicesVerification.evmAlias()).isNull();
            assertThat(alicesVerification.passed()).isTrue();
            final var bobsVerification = argCapture.getValue().verificationFor(bobsKey);
            assertThat(bobsVerification).isNotNull();
            assertThat(bobsVerification.key()).isEqualTo(bobsKey);
            assertThat(bobsVerification.evmAlias()).isNull();
            assertThat(bobsVerification.passed()).isTrue();
        }

        @Test
        @DisplayName("Trigger failing verification, if new key was found")
        void testOptionalNewKeyWithFailingSignature() throws PreCheckException, TimeoutException {
            // given
            final var alicesKey = ALICE.account().keyOrThrow();
            final var bobsKey = BOB.account().keyOrThrow();
            doAnswer(invocation -> {
                        final var context = invocation.getArgument(0, PreHandleContext.class);
                        context.optionalKey(bobsKey);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());
            doAnswer(invocation -> {
                        final var expanded = invocation.getArgument(2, Set.class);
                        expanded.add(ExpandedSignaturePairFactory.ed25519Pair(bobsKey));
                        return null;
                    })
                    .when(signatureExpander)
                    .expand(eq(Set.of(bobsKey)), any(), any());
            final var verificationResults = Map.<Key, SignatureVerificationFuture>of(
                    bobsKey, FakeSignatureVerificationFuture.badFuture(bobsKey));
            when(signatureVerifier.verify(
                            any(),
                            argThat(set -> set.size() == 1
                                    && bobsKey.equals(set.iterator().next().key()))))
                    .thenReturn(verificationResults);

            // when
            workflow.handleRound(state, round);

            // then
            final var argCapture = ArgumentCaptor.forClass(HandleContext.class);
            verify(dispatcher).dispatchHandle(argCapture.capture());
            final var alicesVerification = argCapture.getValue().verificationFor(alicesKey);
            assertThat(alicesVerification).isNotNull();
            assertThat(alicesVerification.key()).isEqualTo(alicesKey);
            assertThat(alicesVerification.evmAlias()).isNull();
            assertThat(alicesVerification.passed()).isTrue();
            final var bobsVerification = argCapture.getValue().verificationFor(bobsKey);
            assertThat(bobsVerification).isNotNull();
            assertThat(bobsVerification.key()).isEqualTo(bobsKey);
            assertThat(bobsVerification.evmAlias()).isNull();
            assertThat(bobsVerification.passed()).isFalse();
        }

        @Test
        void testComplexCase() throws PreCheckException, TimeoutException {
            // given
            final var alicesKey = ALICE.account().keyOrThrow();
            final var bobsKey = BOB.account().keyOrThrow();
            final var carolsKey = CAROL.account().keyOrThrow();
            final var erinsKey = ERIN.account().keyOrThrow();
            final var preHandleVerificationResults = Map.<Key, SignatureVerificationFuture>of(
                    alicesKey, FakeSignatureVerificationFuture.goodFuture(alicesKey),
                    bobsKey, FakeSignatureVerificationFuture.goodFuture(bobsKey),
                    erinsKey, FakeSignatureVerificationFuture.goodFuture(erinsKey));
            final var preHandleResult = new PreHandleResult(
                    ALICE.accountID(),
                    alicesKey,
                    Status.SO_FAR_SO_GOOD,
                    ResponseCodeEnum.OK,
                    new TransactionScenarioBuilder().txInfo(),
                    Set.of(erinsKey),
                    preHandleVerificationResults,
                    null,
                    CONFIG_VERSION);
            when(platformTxn.getMetadata()).thenReturn(preHandleResult);
            doAnswer(invocation -> {
                        final var context = invocation.getArgument(0, PreHandleContext.class);
                        context.requireKey(bobsKey);
                        context.optionalKey(carolsKey);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());
            doAnswer(invocation -> {
                        final var expanded = invocation.getArgument(2, Set.class);
                        expanded.add(ExpandedSignaturePairFactory.ed25519Pair(bobsKey));
                        return null;
                    })
                    .when(signatureExpander)
                    .expand(eq(Set.of(bobsKey)), any(), any());
            doAnswer(invocation -> {
                        final var expanded = invocation.getArgument(2, Set.class);
                        expanded.add(ExpandedSignaturePairFactory.ecdsaPair(carolsKey));
                        return null;
                    })
                    .when(signatureExpander)
                    .expand(eq(Set.of(carolsKey)), any(), any());
            final var verificationResults = Map.<Key, SignatureVerificationFuture>of(
                    carolsKey, FakeSignatureVerificationFuture.goodFuture(carolsKey));
            when(signatureVerifier.verify(
                            any(),
                            argThat(set -> set.size() == 1
                                    && carolsKey.equals(set.iterator().next().key()))))
                    .thenReturn(verificationResults);

            // when
            workflow.handleRound(state, round);

            // then
            final var argCapture = ArgumentCaptor.forClass(HandleContext.class);
            verify(dispatcher).dispatchHandle(argCapture.capture());
            final var alicesVerification = argCapture.getValue().verificationFor(alicesKey);
            assertThat(alicesVerification).isNotNull();
            assertThat(alicesVerification.key()).isEqualTo(alicesKey);
            assertThat(alicesVerification.evmAlias()).isNull();
            assertThat(alicesVerification.passed()).isTrue();
            final var bobsVerification = argCapture.getValue().verificationFor(bobsKey);
            assertThat(bobsVerification).isNotNull();
            assertThat(bobsVerification.key()).isEqualTo(bobsKey);
            assertThat(bobsVerification.evmAlias()).isNull();
            assertThat(bobsVerification.passed()).isTrue();
            final var carolsVerification = argCapture.getValue().verificationFor(carolsKey);
            assertThat(carolsVerification).isNotNull();
            assertThat(carolsVerification.key()).isEqualTo(carolsKey);
            assertThat(carolsVerification.evmAlias()).isNull();
            assertThat(carolsVerification.passed()).isTrue();
            final var erinsVerification = argCapture.getValue().verificationFor(erinsKey);
            assertThat(erinsVerification).isNotNull();
            assertThat(erinsVerification.key()).isEqualTo(erinsKey);
            assertThat(erinsVerification.evmAlias()).isNull();
            assertThat(erinsVerification.passed()).isFalse();
        }
    }

    @Nested
    @DisplayName("Tests for special cases during transaction dispatching")
    final class DispatchTest {
        @Test
        @DisplayName("Charge user, but do not change state otherwise, if transaction causes a HandleException")
        void testHandleException() {
            // when
            doThrow(new HandleException(ResponseCodeEnum.INVALID_SIGNATURE))
                    .when(dispatcher)
                    .dispatchHandle(any());
            workflow.handleRound(state, round);

            // then
            assertThat(aliasesState.isModified()).isFalse();
            // TODO: Check that record was created
        }

        @Test
        @DisplayName("Update receipt, but charge no one, if transaction causes an unexepected exception")
        void testUnknownFailure() {
            // when
            doThrow(new ArrayIndexOutOfBoundsException()).when(dispatcher).dispatchHandle(any());
            workflow.handleRound(state, round);

            // then
            assertThat(accountsState.isModified()).isFalse();
            assertThat(aliasesState.isModified()).isFalse();
            // TODO: Check receipt
        }
    }

    @Nested
    @DisplayName("Tests for checking the interaction with the record manager")
    final class RecordManagerInteractionTest {

        // TODO: Add more tests to make sure we produce the right input for the recordManger (once it is implemented)
        // https://github.com/hashgraph/hedera-services/issues/6746

        @Test
        void testSimpleRun() {
            // when
            workflow.handleRound(state, round);

            // then
            verify(blockRecordManager).startUserTransaction(eq(CONSENSUS_NOW), eq(state));
            verify(blockRecordManager).endUserTransaction(any(), eq(state));
            verify(blockRecordManager).endRound(eq(state));
        }
    }
}
