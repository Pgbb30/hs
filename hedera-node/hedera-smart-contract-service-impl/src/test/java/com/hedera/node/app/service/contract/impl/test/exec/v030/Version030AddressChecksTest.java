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

package com.hedera.node.app.service.contract.impl.test.exec.v030;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HTS_PRECOMPILE_ADDRESS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.v030.Version030AddressChecks;
import java.util.Map;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Version030AddressChecksTest {
    @Mock
    private PrecompiledContract mockHtsPrecompile;

    @Mock
    private PrecompiledContract mockPrngPrecompile;

    @Mock
    private PrecompiledContract mockRatesPrecompile;

    @Mock
    private MessageFrame frame;

    @Mock
    private WorldUpdater worldUpdater;

    @Mock
    private Account account;

    private Version030AddressChecks subject;

    @BeforeEach
    void setUp() {
        subject = new Version030AddressChecks(Map.of(
                HTS_PRECOMPILE_ADDRESS,
                mockHtsPrecompile,
                Address.fromHexString("0x168"),
                mockRatesPrecompile,
                Address.fromHexString("0x169"),
                mockPrngPrecompile));
    }

    @Test
    void classifiesPrecompiles() {
        assertTrue(subject.isHederaPrecompile(HTS_PRECOMPILE_ADDRESS));
        assertTrue(subject.isHederaPrecompile(Address.fromHexString("0x168")));
        assertTrue(subject.isHederaPrecompile(Address.fromHexString("0x169")));
        assertFalse(subject.isHederaPrecompile(Address.fromHexString("0x16a")));
    }

    @Test
    void precompilesAlwaysPresent() {
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        assertTrue(subject.isPresent(HTS_PRECOMPILE_ADDRESS, frame));
        assertTrue(subject.isPresent(Address.fromHexString("0x168"), frame));
        assertTrue(subject.isPresent(Address.fromHexString("0x169"), frame));
        assertFalse(subject.isPresent(Address.fromHexString("0x16a"), frame));
    }

    @Test
    void nonNullAccountIsPresent() {
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.get(EIP_1014_ADDRESS)).willReturn(account);
        assertTrue(subject.isPresent(EIP_1014_ADDRESS, frame));
    }

    @Test
    void nothingIsSystemAccount() {
        assertFalse(subject.isSystemAccount(HTS_PRECOMPILE_ADDRESS));
    }
}
