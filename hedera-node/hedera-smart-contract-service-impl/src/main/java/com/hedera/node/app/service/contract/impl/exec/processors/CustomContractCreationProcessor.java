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

package com.hedera.node.app.service.contract.impl.exec.processors;

import static com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule.INITIAL_CONTRACT_NONCE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.contractvalidation.ContractValidationRule;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;

/**
 * A customization of the Besu {@link ContractCreationProcessor} that replaces the
 * explicit {@code sender.decrementBalance(frame.getValue())} and
 * {@code contract.incrementBalance(frame.getValue())} calls with a single call
 * to the {@link HederaWorldUpdater#tryTransferFromContract(Address, Address, long, boolean)}
 * dispatch method.
 */
public class CustomContractCreationProcessor extends ContractCreationProcessor {
    // By convention, the halt reason should be INSUFFICIENT_GAS when the contract already exists
    private static final Optional<ExceptionalHaltReason> COLLISION_HALT_REASON =
            Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS);

    public CustomContractCreationProcessor(
            @NonNull final EVM evm,
            @NonNull final GasCalculator gasCalculator,
            final boolean requireCodeDepositToSucceed,
            @NonNull final List<ContractValidationRule> contractValidationRules,
            final long initialContractNonce) {
        super(
                requireNonNull(gasCalculator),
                requireNonNull(evm),
                requireCodeDepositToSucceed,
                requireNonNull(contractValidationRules),
                initialContractNonce);
    }

    @Override
    public void start(@NonNull final MessageFrame frame, @NonNull final OperationTracer tracer) {
        final var addressToCreate = frame.getContractAddress();
        final var contract =
                frame.getWorldUpdater().getOrCreate(addressToCreate).getMutable();

        if (alreadyCreated(contract)) {
            halt(frame, COLLISION_HALT_REASON);
            tracer.traceAccountCreationResult(frame, COLLISION_HALT_REASON);
        } else {
            final var updater = proxyUpdaterFor(frame);
            // A contract creation is never a delegate call, hence the false argument below
            final var maybeReasonToHalt = updater.tryTransferFromContract(
                    frame.getSenderAddress(), addressToCreate, frame.getValue().toLong(), false);
            if (maybeReasonToHalt.isPresent()) {
                // Besu doesn't trace the creation on a modification exception, so seems
                // like we shouldn't do it here either; but may need a bit more consideration
                halt(frame, maybeReasonToHalt);
            } else {
                contract.setNonce(INITIAL_CONTRACT_NONCE);
                frame.setState(MessageFrame.State.CODE_EXECUTING);
            }
        }
    }

    private void halt(final MessageFrame frame, @NonNull final Optional<ExceptionalHaltReason> reason) {
        frame.setState(MessageFrame.State.EXCEPTIONAL_HALT);
        frame.setExceptionalHaltReason(reason);
    }

    private boolean alreadyCreated(final MutableAccount account) {
        return account.getNonce() > 0 || account.getCode().size() > 0;
    }
}
