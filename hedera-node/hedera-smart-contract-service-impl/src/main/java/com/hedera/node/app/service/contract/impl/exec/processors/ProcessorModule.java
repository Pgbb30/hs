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

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import javax.inject.Singleton;
import org.hyperledger.besu.evm.contractvalidation.ContractValidationRule;
import org.hyperledger.besu.evm.contractvalidation.MaxCodeSizeRule;
import org.hyperledger.besu.evm.contractvalidation.PrefixCodeRule;

@Module
public interface ProcessorModule {
    long INITIAL_CONTRACT_NONCE = 1L;
    boolean REQUIRE_CODE_DEPOSIT_TO_SUCCEED = true;

    @Provides
    @Singleton
    @IntoSet
    static ContractValidationRule provideMaxCodeSizeRule() {
        return MaxCodeSizeRule.of(0x6000);
    }

    @Provides
    @Singleton
    @IntoSet
    static ContractValidationRule providePrefixCodeRule() {
        return PrefixCodeRule.of();
    }
}
