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

package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.MISSING_ADDRESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.spi.meta.bni.Dispatch;
import com.hedera.node.app.spi.meta.bni.VerificationStrategy;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.FixedStack;
import org.hyperledger.besu.evm.operation.CallOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;

/**
 * A Hedera customization of {@link CallOperation} that, if lazy creation is enabled and
 * applies to a call, does no additional address checks. Otherwise, only allows calls to an
 * address that is either a Hedera precompile, a system address, or not missing.
 *
 * <p><b>IMPORTANT:</b> This operation no longer enforces for receiver signature requirements
 * when value is being transferred; that will now happen in the call the {@link MessageCallProcessor}
 * makes to {@link Dispatch#transferWithReceiverSigCheck(long, long, long, VerificationStrategy)}.
 */
public class CustomCallOperation extends CallOperation {
    private static final Operation.OperationResult UNDERFLOW_RESPONSE =
            new Operation.OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);

    private final FeatureFlags featureFlags;
    private final AddressChecks addressChecks;

    public CustomCallOperation(
            @NonNull final FeatureFlags featureFlags,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final AddressChecks addressChecks) {
        super(gasCalculator);
        this.featureFlags = Objects.requireNonNull(featureFlags);
        this.addressChecks = Objects.requireNonNull(addressChecks);
    }

    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        try {
            final var toAddress = to(frame);
            final var isMissing = mustBePresent(frame, toAddress) && !addressChecks.isPresent(toAddress, frame);
            return isMissing ? new Operation.OperationResult(cost(frame), MISSING_ADDRESS) : super.execute(frame, evm);
        } catch (final FixedStack.UnderflowException ignore) {
            return UNDERFLOW_RESPONSE;
        }
    }

    private boolean mustBePresent(@NonNull final MessageFrame frame, @NonNull final Address toAddress) {
        // This call will create the "to" address, so it doesn't need to be present
        if (impliesLazyCreation(frame, toAddress) && featureFlags.isImplicitCreationEnabled(frame)) {
            return false;
        }
        // Let system accounts through so the message call processor can fail in a more legible way
        return !addressChecks.isSystemAccount(toAddress);
    }

    private boolean impliesLazyCreation(@NonNull final MessageFrame frame, @NonNull final Address toAddress) {
        return !isLongZero(toAddress)
                && value(frame).greaterThan(Wei.ZERO)
                && !addressChecks.isPresent(toAddress, frame);
    }
}
