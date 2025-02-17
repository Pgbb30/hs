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

package com.hedera.node.app.service.contract.impl;

import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_030;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_034;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_038;

import com.hedera.node.app.service.contract.impl.annotations.ServicesV030;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV034;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV038;
import com.hedera.node.app.service.contract.impl.annotations.ServicesVersionKey;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule;
import com.hedera.node.app.service.contract.impl.exec.v030.V030Module;
import com.hedera.node.app.service.contract.impl.exec.v034.V034Module;
import com.hedera.node.app.service.contract.impl.exec.v038.V038Module;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;
import dagger.multibindings.Multibinds;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import javax.inject.Singleton;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;

/**
 * Provides bindings for the {@link TransactionProcessor} implementations used by each
 * version of the Hedera EVM, along with infrastructure like the {@link GasCalculator}
 * and Hedera {@link PrecompiledContract} instances that have not changed since the
 * first EVM version we explicitly support (which is {@code v0.30}).
 */
@Module(includes = {V030Module.class, V034Module.class, V038Module.class, ProcessorModule.class})
public interface ServiceModule {
    @Binds
    @Singleton
    GasCalculator bindGasCalculator(@NonNull final CustomGasCalculator gasCalculator);

    @Multibinds
    Map<Address, PrecompiledContract> bindHederaPrecompiles();

    @Binds
    @IntoMap
    @ServicesVersionKey(VERSION_030)
    TransactionProcessor bindV030Processor(@ServicesV030 @NonNull final TransactionProcessor processor);

    @Binds
    @IntoMap
    @ServicesVersionKey(VERSION_034)
    TransactionProcessor bindV034Processor(@ServicesV034 @NonNull final TransactionProcessor processor);

    @Binds
    @IntoMap
    @ServicesVersionKey(VERSION_038)
    TransactionProcessor bindV038Processor(@ServicesV038 @NonNull final TransactionProcessor processor);
}
