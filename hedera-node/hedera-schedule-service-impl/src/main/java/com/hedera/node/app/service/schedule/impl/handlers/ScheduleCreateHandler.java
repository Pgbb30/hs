/*
 * Copyright (C) 2022-2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.schedule.impl.handlers;

import static com.hedera.node.app.service.schedule.impl.Utils.asOrdinary;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PreHandleDispatcher;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#SCHEDULE_CREATE}.
 */
@Singleton
public class ScheduleCreateHandler extends AbstractScheduleHandler implements TransactionHandler {

    // @todo('6249') This constructor should be removed and the @Inject annotation added to the remaining constructor
    @Inject
    public ScheduleCreateHandler(@NonNull final PreHandleDispatcher dispatcher) {
        super(dispatcher);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.scheduleCreateOrThrow();

        // If there is an admin key, then it must have signed the transaction
        if (op.hasAdminKey()) {
            context.requireKeyOrThrow(op.adminKeyOrThrow(), ResponseCodeEnum.INVALID_ADMIN_KEY);
        }

        final var scheduledTxn = asOrdinary(
                op.scheduledTransactionBodyOrElse(SchedulableTransactionBody.DEFAULT),
                txn.transactionIDOrElse(TransactionID.DEFAULT));

        /* We need to always add the custom payer to the sig requirements even if it equals the to level transaction
        payer. It is still part of the "other" parties, and we need to know to store it's key with the
        schedule in all cases. This fixes a case where the ScheduleCreate payer and the custom payer are
        the same payer, which would cause the custom payers signature to not get stored and then a ScheduleSign
        would not execute the transaction without and extra signature from the custom payer.*/
        final var payerForNested = op.hasPayerAccountID()
                ? op.payerAccountIDOrElse(AccountID.DEFAULT)
                : txn.transactionIDOrElse(TransactionID.DEFAULT).accountIDOrElse(AccountID.DEFAULT);

        // FUTURE: Once we allow schedule transactions to be scheduled inside, we need a check here
        // to see if provided payer is same as payer in the inner transaction.
        preHandleScheduledTxn(context, scheduledTxn, payerForNested);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        throw new UnsupportedOperationException("Not implemented");
    }
}
