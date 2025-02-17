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

package com.hedera.node.app.service.contract.impl.test.state;

import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.node.app.service.contract.impl.infra.LegibleStorageManager;
import com.hedera.node.app.service.contract.impl.infra.RentCalculator;
import com.hedera.node.app.service.contract.impl.infra.StorageSizeValidator;
import com.hedera.node.app.service.contract.impl.state.BaseProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.ContractSchema;
import com.hedera.node.app.service.contract.impl.state.EvmFrameState;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStateFactory;
import com.hedera.node.app.service.contract.impl.state.RentFactors;
import com.hedera.node.app.service.contract.impl.state.StorageAccess;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.node.app.service.contract.impl.state.StorageSizeChange;
import com.hedera.node.app.spi.meta.bni.Dispatch;
import com.hedera.node.app.spi.meta.bni.Fees;
import com.hedera.node.app.spi.meta.bni.Scope;
import com.hedera.node.app.spi.state.WritableKVState;
import com.hedera.node.app.spi.state.WritableStates;
import java.util.List;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.BDDMockito;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BaseProxyWorldUpdaterTest {
    private static final long A_NUM = 123L;
    private static final long B_NUM = 234L;
    private static final UInt256 A_KEY_BEING_ADDED = UInt256.fromHexString("0x1234");
    private static final UInt256 A_KEY_BEING_CHANGED = UInt256.fromHexString("0x2345");
    private static final UInt256 A_KEY_BEING_REMOVED = UInt256.fromHexString("0x3456");
    private static final UInt256 A_SECOND_KEY_BEING_ADDED = UInt256.fromHexString("0x4567");
    private static final UInt256 A_THIRD_KEY_BEING_ADDED = UInt256.fromHexString("0x7654");
    private static final UInt256 B_KEY_BEING_ADDED = UInt256.fromHexString("0x5678");
    private static final UInt256 B_KEY_BEING_REMOVED = UInt256.fromHexString("0x6789");
    private static final UInt256 B_SECOND_KEY_BEING_REMOVED = UInt256.fromHexString("0x7890");

    @Mock
    private RentCalculator rentCalculator;

    @Mock
    private LegibleStorageManager storageManager;

    @Mock
    private StorageSizeValidator storageSizeValidator;

    @Mock
    private EvmFrameState evmFrameState;

    @Mock
    private EvmFrameStateFactory evmFrameStateFactory;

    @Mock
    private Fees fees;

    @Mock
    private Scope scope;

    @Mock
    private Dispatch dispatch;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableKVState<SlotKey, SlotValue> storage;

    private BaseProxyWorldUpdater subject;

    @BeforeEach
    void setUp() {
        given(evmFrameStateFactory.createIn(scope)).willReturn(evmFrameState);

        subject = new BaseProxyWorldUpdater(
                scope, evmFrameStateFactory, rentCalculator, storageManager, storageSizeValidator);
    }

    @Test
    void performsAdditionalCommitActionsInOrder() {
        InOrder inOrder = BDDMockito.inOrder(storageSizeValidator, storageManager, rentCalculator, scope, dispatch);

        final var aExpiry = 1_234_567;
        final var aSlotsUsedBeforeCommit = 101;
        final var sizeIncludingPendingRemovals = 123L;
        // Three keys being removed in pending changes
        final var sizeExcludingPendingRemovals = sizeIncludingPendingRemovals - 3;
        given(evmFrameState.getKvStateSize()).willReturn(sizeIncludingPendingRemovals);
        given(evmFrameState.getStorageChanges()).willReturn(pendingChanges());
        given(evmFrameState.getRentFactorsFor(A_NUM)).willReturn(new RentFactors(aSlotsUsedBeforeCommit, aExpiry));

        final var rentInTinycents = 666_666L;
        final var rentInTinybars = 111_111L;
        // A contract is allocating 2 slots net
        given(rentCalculator.computeFor(sizeExcludingPendingRemovals, 2, aSlotsUsedBeforeCommit, aExpiry))
                .willReturn(rentInTinycents);
        given(scope.fees()).willReturn(fees);
        given(scope.dispatch()).willReturn(dispatch);
        given(fees.costInTinybars(rentInTinycents)).willReturn(rentInTinybars);

        given(scope.writableContractState()).willReturn(writableStates);
        given(writableStates.<SlotKey, SlotValue>get(ContractSchema.STORAGE_KEY))
                .willReturn(storage);

        subject.commit();

        inOrder.verify(storageSizeValidator).assertValid(sizeExcludingPendingRemovals, scope, expectedSizeChanges());
        inOrder.verify(dispatch).chargeStorageRent(A_NUM, rentInTinybars, true);
        inOrder.verify(storageManager).rewrite(scope, pendingChanges(), expectedSizeChanges(), storage);
        inOrder.verify(scope).commit();
    }

    private List<StorageAccesses> pendingChanges() {
        return List.of(
                new StorageAccesses(
                        A_NUM,
                        List.of(
                                new StorageAccess(A_KEY_BEING_ADDED, UInt256.ZERO, UInt256.ONE),
                                new StorageAccess(A_KEY_BEING_CHANGED, UInt256.ONE, UInt256.MAX_VALUE),
                                new StorageAccess(A_KEY_BEING_REMOVED, UInt256.ONE, UInt256.ZERO),
                                new StorageAccess(A_SECOND_KEY_BEING_ADDED, UInt256.ZERO, UInt256.ONE),
                                new StorageAccess(A_THIRD_KEY_BEING_ADDED, UInt256.ZERO, UInt256.MAX_VALUE))),
                new StorageAccesses(
                        B_NUM,
                        List.of(
                                new StorageAccess(B_KEY_BEING_ADDED, UInt256.ZERO, UInt256.ONE),
                                new StorageAccess(B_KEY_BEING_REMOVED, UInt256.ONE, UInt256.ZERO),
                                new StorageAccess(B_SECOND_KEY_BEING_REMOVED, UInt256.ONE, UInt256.ZERO))));
    }

    private List<StorageSizeChange> expectedSizeChanges() {
        return List.of(new StorageSizeChange(A_NUM, 1, 3), new StorageSizeChange(B_NUM, 2, 1));
    }
}
