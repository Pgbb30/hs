/*
 * Copyright (C) 2020-2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl;

import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionProcessor;
import com.hedera.node.app.service.contract.impl.state.ContractSchema;
import com.hedera.node.app.spi.state.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Standard implementation of the {@link ContractService}.
 */
public final class ContractServiceImpl implements ContractService {
    private final HederaEvmTransactionProcessor transactionProcessor;

    public ContractServiceImpl() {
        transactionProcessor = DaggerServiceComponent.create().transactionProcessor();
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new ContractSchema());
    }

    public HederaEvmTransactionProcessor transactionProcessor() {
        return transactionProcessor;
    }
}
