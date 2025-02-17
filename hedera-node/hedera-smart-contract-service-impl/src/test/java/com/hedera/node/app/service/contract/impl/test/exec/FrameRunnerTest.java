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

package com.hedera.node.app.service.contract.impl.test.exec;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.TOO_MANY_CHILD_RECORDS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.FrameRunner;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.hevm.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.*;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FrameRunnerTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private ProxyWorldUpdater worldUpdater;

    @Mock
    private MessageFrame childFrame;

    @Mock
    private ActionSidecarContentTracer tracer;

    @Mock
    private CustomMessageCallProcessor messageCallProcessor;

    @Mock
    private ContractCreationProcessor contractCreationProcessor;

    @Mock
    private CustomGasCalculator gasCalculator;

    private FrameRunner subject;

    @BeforeEach
    void setUp() {
        subject = new FrameRunner(gasCalculator);
    }

    @Test
    void happyPathWorksWithEip1014Receiver() {
        final var inOrder = Mockito.inOrder(frame, childFrame, tracer, messageCallProcessor, contractCreationProcessor);

        givenBaseSuccessWith(EIP_1014_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getHederaContractId(EIP_1014_ADDRESS)).willReturn(CALLED_CONTRACT_ID);

        final var result =
                subject.runToCompletion(GAS_LIMIT, frame, tracer, messageCallProcessor, contractCreationProcessor);

        inOrder.verify(tracer).traceOriginAction(frame);
        inOrder.verify(contractCreationProcessor).process(frame, tracer);
        inOrder.verify(messageCallProcessor).process(childFrame, tracer);
        inOrder.verify(tracer).sanitizeTracedActions(frame);

        assertTrue(result.isSuccess());
        assertEquals(expectedGasUsed(frame), result.gasUsed());
        assertEquals(pbjLogsFrom(List.of(BESU_LOG)), result.logs());
        assertEquals(CALLED_CONTRACT_ID, result.recipientId());
        assertEquals(CALLED_CONTRACT_EVM_ADDRESS, result.recipientEvmAddress());

        assertSuccessExpectationsWith(CALLED_CONTRACT_ID, CALLED_CONTRACT_EVM_ADDRESS, frame, result);
    }

    @Test
    void happyPathWorksWithLongZeroReceiver() {
        final var inOrder = Mockito.inOrder(frame, childFrame, tracer, messageCallProcessor, contractCreationProcessor);

        givenBaseSuccessWith(NON_SYSTEM_LONG_ZERO_ADDRESS);

        final var result =
                subject.runToCompletion(GAS_LIMIT, frame, tracer, messageCallProcessor, contractCreationProcessor);

        inOrder.verify(tracer).traceOriginAction(frame);
        inOrder.verify(contractCreationProcessor).process(frame, tracer);
        inOrder.verify(messageCallProcessor).process(childFrame, tracer);
        inOrder.verify(tracer).sanitizeTracedActions(frame);

        assertSuccessExpectationsWith(
                NON_SYSTEM_CONTRACT_ID, asEvmContractId(NON_SYSTEM_LONG_ZERO_ADDRESS), frame, result);
    }

    @Test
    void failurePathWorksWithRevertReason() {
        final var inOrder = Mockito.inOrder(frame, childFrame, tracer, messageCallProcessor, contractCreationProcessor);

        givenBaseFailureWith(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frame.getRevertReason()).willReturn(Optional.of(SOME_REVERT_REASON));

        final var result =
                subject.runToCompletion(GAS_LIMIT, frame, tracer, messageCallProcessor, contractCreationProcessor);

        inOrder.verify(tracer).traceOriginAction(frame);
        inOrder.verify(contractCreationProcessor).process(frame, tracer);
        inOrder.verify(messageCallProcessor).process(childFrame, tracer);
        inOrder.verify(tracer).sanitizeTracedActions(frame);

        assertFailureExpectationsWith(frame, result);
        assertEquals(tuweniToPbjBytes(SOME_REVERT_REASON), result.revertReason());
        assertNull(result.haltReason());
    }

    @Test
    void failurePathWorksWithHaltReason() {
        final var inOrder = Mockito.inOrder(frame, childFrame, tracer, messageCallProcessor, contractCreationProcessor);

        givenBaseFailureWith(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frame.getExceptionalHaltReason()).willReturn(Optional.of(TOO_MANY_CHILD_RECORDS));

        final var result =
                subject.runToCompletion(GAS_LIMIT, frame, tracer, messageCallProcessor, contractCreationProcessor);

        inOrder.verify(tracer).traceOriginAction(frame);
        inOrder.verify(contractCreationProcessor).process(frame, tracer);
        inOrder.verify(messageCallProcessor).process(childFrame, tracer);
        inOrder.verify(tracer).sanitizeTracedActions(frame);

        assertFailureExpectationsWith(frame, result);
        assertEquals(TOO_MANY_CHILD_RECORDS.toString(), result.haltReason());
        assertNull(result.revertReason());
    }

    private void assertSuccessExpectationsWith(
            @NonNull final ContractID expectedReceiverId,
            @NonNull final ContractID expectedReceiverAddress,
            @NonNull final MessageFrame frame,
            @NonNull final HederaEvmTransactionResult result) {
        assertTrue(result.isSuccess());
        assertEquals(expectedGasUsed(frame), result.gasUsed());
        assertEquals(pbjLogsFrom(List.of(BESU_LOG)), result.logs());
        assertEquals(expectedReceiverId, result.recipientId());
        assertEquals(expectedReceiverAddress, result.recipientEvmAddress());
        assertEquals(OUTPUT_DATA, result.output());
    }

    private void assertFailureExpectationsWith(
            @NonNull final MessageFrame frame, @NonNull final HederaEvmTransactionResult result) {
        assertFalse(result.isSuccess());
        assertEquals(expectedGasUsed(frame), result.gasUsed());
        assertEquals(Bytes.EMPTY, result.output());
    }

    private void givenBaseSuccessWith(@NonNull final Address receiver) {
        givenBaseScenarioWithDetails(receiver, true);
    }

    private void givenBaseFailureWith(@NonNull final Address receiver) {
        givenBaseScenarioWithDetails(receiver, false);
    }

    private void givenBaseScenarioWithDetails(@NonNull final Address receiver, final boolean sucess) {
        final Deque<MessageFrame> messageFrameStack = new ArrayDeque<>();
        given(frame.getType()).willReturn(MessageFrame.Type.CONTRACT_CREATION);
        given(childFrame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        doAnswer(invocation -> {
                    messageFrameStack.pop();
                    messageFrameStack.push(childFrame);
                    return null;
                })
                .when(contractCreationProcessor)
                .process(frame, tracer);
        doAnswer(invocation -> {
                    messageFrameStack.pop();
                    return null;
                })
                .when(messageCallProcessor)
                .process(childFrame, tracer);
        given(gasCalculator.getSelfDestructRefundAmount()).willReturn(GAS_LIMIT / 32);
        given(gasCalculator.getMaxRefundQuotient()).willReturn(BESU_MAX_REFUND_QUOTIENT);
        given(frame.getRemainingGas()).willReturn(GAS_LIMIT / 2);
        given(frame.getSelfDestructs()).willReturn(Set.of(EIP_1014_ADDRESS, NON_SYSTEM_LONG_ZERO_ADDRESS));
        given(frame.getGasRefund()).willReturn(GAS_LIMIT / 8);
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.maxRefundPercentOfGasLimit", HEDERA_MAX_REFUND_PERCENTAGE)
                .getOrCreateConfig();
        given(frame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE)).willReturn(config);
        given(frame.getGasPrice()).willReturn(Wei.of(NETWORK_GAS_PRICE));
        if (sucess) {
            given(frame.getState()).willReturn(MessageFrame.State.COMPLETED_SUCCESS);
            given(frame.getLogs()).willReturn(List.of(BESU_LOG));
            given(frame.getOutputData()).willReturn(pbjToTuweniBytes(OUTPUT_DATA));
        } else {
            given(frame.getState()).willReturn(MessageFrame.State.COMPLETED_FAILED);
        }
        given(frame.getRecipientAddress()).willReturn(receiver);
        given(frame.getMessageFrameStack()).willReturn(messageFrameStack);
    }

    private long expectedGasUsed(@NonNull final MessageFrame frame) {
        var nominalUsage = GAS_LIMIT - frame.getRemainingGas();
        final var selfDestructRefund = gasCalculator.getSelfDestructRefundAmount()
                * Math.min(frame.getSelfDestructs().size(), nominalUsage / gasCalculator.getMaxRefundQuotient());
        nominalUsage -= (selfDestructRefund + frame.getGasRefund());
        return Math.max(nominalUsage, GAS_LIMIT - GAS_LIMIT * HEDERA_MAX_REFUND_PERCENTAGE / 100);
    }
}
