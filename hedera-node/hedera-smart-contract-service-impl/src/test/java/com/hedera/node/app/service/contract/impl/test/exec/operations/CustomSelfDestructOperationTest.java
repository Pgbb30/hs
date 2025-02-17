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

package com.hedera.node.app.service.contract.impl.test.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_RECEIVER_SIGNATURE;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.SELFDESTRUCT_TO_SELF;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSameResult;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomSelfDestructOperation;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.FixedStack;
import org.hyperledger.besu.evm.operation.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomSelfDestructOperationTest {
    private static final long COLD_ACCESS_COST = 1L;
    private static final Wei INHERITANCE = Wei.of(666L);
    private static final Address TBD = Address.fromHexString("0xa234567890abcdefa234567890abcdefa2345678");
    private static final Address BENEFICIARY = Address.fromHexString("0x1234567890abcdef1234567890abcdef12345678");

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private AddressChecks addressChecks;

    @Mock
    private MessageFrame frame;

    @Mock
    private EVM evm;

    @Mock
    private ProxyWorldUpdater proxyWorldUpdater;

    @Mock
    private Account account;

    private CustomSelfDestructOperation subject;

    @BeforeEach
    void setUp() {
        subject = new CustomSelfDestructOperation(gasCalculator, addressChecks);
    }

    @Test
    void catchesUnderflowWhenStackIsEmpty() {
        given(frame.popStackItem()).willThrow(FixedStack.UnderflowException.class);
        final var expected = new Operation.OperationResult(0L, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void rejectsSystemBeneficiaryAsMissing() {
        given(frame.popStackItem()).willReturn(BENEFICIARY);
        given(addressChecks.isSystemAccount(BENEFICIARY)).willReturn(true);
        given(gasCalculator.selfDestructOperationGasCost(null, Wei.ZERO)).willReturn(123L);
        final var expected = new Operation.OperationResult(123L, CustomExceptionalHaltReason.MISSING_ADDRESS);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void respectsHederaCustomHaltReason() {
        given(frame.popStackItem()).willReturn(BENEFICIARY);
        given(frame.getRecipientAddress()).willReturn(TBD);
        given(addressChecks.isPresent(BENEFICIARY, frame)).willReturn(true);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        given(gasCalculator.selfDestructOperationGasCost(null, Wei.ZERO)).willReturn(123L);
        given(proxyWorldUpdater.tryTrackingDeletion(TBD, BENEFICIARY)).willReturn(Optional.of(SELFDESTRUCT_TO_SELF));
        final var expected = new Operation.OperationResult(123L, SELFDESTRUCT_TO_SELF);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void rejectsSelfDestructInStaticChanges() {
        givenRunnableSelfDestruct();
        given(frame.isStatic()).willReturn(true);
        given(gasCalculator.getColdAccountAccessCost()).willReturn(COLD_ACCESS_COST);
        given(gasCalculator.selfDestructOperationGasCost(null, INHERITANCE)).willReturn(123L);
        final var expected =
                new Operation.OperationResult(123L + COLD_ACCESS_COST, ExceptionalHaltReason.ILLEGAL_STATE_CHANGE);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void haltsSelfDestructWithInsufficientGas() {
        givenRunnableSelfDestruct();
        given(frame.warmUpAddress(BENEFICIARY)).willReturn(true);
        given(gasCalculator.selfDestructOperationGasCost(null, INHERITANCE)).willReturn(123L);
        final var expected = new Operation.OperationResult(123L, INSUFFICIENT_GAS);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void haltsSelfDestructOnFailedInheritanceTransfer() {
        givenRunnableSelfDestruct();
        givenWarmBeneficiaryWithSufficientGas();
        given(proxyWorldUpdater.tryTransferFromContract(TBD, BENEFICIARY, INHERITANCE.toLong(), true))
                .willReturn(Optional.of(INVALID_RECEIVER_SIGNATURE));
        final var expected = new Operation.OperationResult(123L, INVALID_RECEIVER_SIGNATURE);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void finalizesFrameAsExpected() {
        givenRunnableSelfDestruct();
        givenWarmBeneficiaryWithSufficientGas();
        given(frame.getContractAddress()).willReturn(TBD);
        given(proxyWorldUpdater.tryTransferFromContract(TBD, BENEFICIARY, INHERITANCE.toLong(), false))
                .willReturn(Optional.empty());
        final var expected = new Operation.OperationResult(123L, null);
        assertSameResult(expected, subject.execute(frame, evm));
        verify(frame).addSelfDestruct(TBD);
        verify(frame).addRefund(BENEFICIARY, INHERITANCE);
        verify(frame).setState(MessageFrame.State.CODE_SUCCESS);
    }

    private void givenWarmBeneficiaryWithSufficientGas() {
        given(frame.warmUpAddress(BENEFICIARY)).willReturn(true);
        given(frame.getRemainingGas()).willReturn(666L);
        given(gasCalculator.selfDestructOperationGasCost(null, INHERITANCE)).willReturn(123L);
    }

    private void givenRunnableSelfDestruct() {
        given(frame.popStackItem()).willReturn(BENEFICIARY);
        given(frame.getRecipientAddress()).willReturn(TBD);
        given(addressChecks.isPresent(BENEFICIARY, frame)).willReturn(true);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        given(proxyWorldUpdater.tryTrackingDeletion(TBD, BENEFICIARY)).willReturn(Optional.empty());
        given(proxyWorldUpdater.get(TBD)).willReturn(account);
        given(account.getBalance()).willReturn(INHERITANCE);
    }
}
