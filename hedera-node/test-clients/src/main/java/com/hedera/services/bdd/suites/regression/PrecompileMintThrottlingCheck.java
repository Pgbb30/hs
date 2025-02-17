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

package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.spec.HapiSpec.propertyPreservingHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.suites.contract.precompile.ContractMintHTSSuite.MINT_NFT_CONTRACT;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.ThrottleDefsLoader.protoDefsFromResource;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class PrecompileMintThrottlingCheck extends HapiSuite {

    private static final Logger LOG = LogManager.getLogger(PrecompileMintThrottlingCheck.class);
    private final AtomicLong duration = new AtomicLong(10);
    private final AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);
    private final AtomicInteger maxOpsPerSec = new AtomicInteger(50);
    private static final int EXPECTED_MAX_MINTS_PER_SEC = 50;
    private static final double ALLOWED_THROTTLE_NOISE_TOLERANCE = 0.05;
    private static final String NON_FUNGIBLE_TOKEN = "NON_FUNGIBLE_TOKEN";
    public static final int GAS_TO_OFFER = 1_000_000;

    public static void main(String... args) {
        new PrecompileMintThrottlingCheck().runSuiteSync();
    }

    @Override
    public List<HapiSpec> getSpecsInSuite() {
        return List.of(precompileNftMintsAreLimitedByConsThrottle());
    }

    @SuppressWarnings("java:S5960")
    private HapiSpec precompileNftMintsAreLimitedByConsThrottle() {
        var mainnetLimits = protoDefsFromResource("testSystemFiles/mainnet-throttles.json");
        return propertyPreservingHapiSpec("PrecompileNftMintsAreLimitedByConsThrottle")
                .preserving("contracts.throttle.throttleByGas")
                .given(
                        overriding("contracts.throttle.throttleByGas", "false"),
                        fileUpdate(THROTTLE_DEFS).payingWith(GENESIS).contents(mainnetLimits.toByteArray()))
                .when(runWithProvider(precompileMintsFactory())
                        .lasting(duration::get, unit::get)
                        .maxOpsPerSec(maxOpsPerSec::get))
                .then(getTokenInfo(NON_FUNGIBLE_TOKEN)
                        .hasTotalSupplySatisfying(supply -> {
                            final var allowedMaxSupply = (int) (unit.get().toSeconds(duration.get())
                                    * EXPECTED_MAX_MINTS_PER_SEC
                                    * (1.0 + ALLOWED_THROTTLE_NOISE_TOLERANCE));
                            final var allowedMinSupply = (int) (unit.get().toSeconds(duration.get())
                                    * EXPECTED_MAX_MINTS_PER_SEC
                                    * (1.0 - ALLOWED_THROTTLE_NOISE_TOLERANCE));
                            Assertions.assertTrue(
                                    supply <= allowedMaxSupply,
                                    String.format(
                                            "Expected max supply to be less than %d, but was %d",
                                            allowedMaxSupply, supply));
                            Assertions.assertTrue(
                                    supply >= allowedMinSupply,
                                    String.format(
                                            "Expected min supply to be at least %d, but was %d",
                                            allowedMinSupply, supply));
                        })
                        .logged());
    }

    private Function<HapiSpec, OpProvider> precompileMintsFactory() {
        final AtomicReference<Address> mintContractAddress = new AtomicReference<>();
        final List<byte[]> someMetadata =
                IntStream.range(0, 100).mapToObj(TxnUtils::randomUtf8Bytes).toList();
        final SplittableRandom r = new SplittableRandom();
        return spec -> new OpProvider() {
            @Override
            public List<HapiSpecOperation> suggestedInitializers() {
                return List.of(
                        uploadInitCode(MINT_NFT_CONTRACT),
                        contractCreate(MINT_NFT_CONTRACT).gas(GAS_TO_OFFER),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .contractKey(Set.of(TokenKeyType.SUPPLY_KEY), MINT_NFT_CONTRACT)
                                .initialSupply(0)
                                .exposingAddressTo(mintContractAddress::set),
                        getTokenInfo(NON_FUNGIBLE_TOKEN).logged());
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                final var numMetadataThisMint = r.nextInt(1, 11);
                final var metadata = r.ints(numMetadataThisMint, 0, someMetadata.size())
                        .mapToObj(someMetadata::get)
                        .toArray(byte[][]::new);
                var op = contractCall(
                                MINT_NFT_CONTRACT,
                                "mintNonFungibleTokenWithAddress",
                                mintContractAddress.get(),
                                metadata)
                        .gas(2L * GAS_TO_OFFER)
                        .payingWith(GENESIS)
                        .noLogging()
                        .deferStatusResolution()
                        .hasKnownStatusFrom(SUCCESS, CONTRACT_REVERT_EXECUTED);

                return Optional.of(op);
            }
        };
    }

    @Override
    protected Logger getResultsLogger() {
        return LOG;
    }
}
