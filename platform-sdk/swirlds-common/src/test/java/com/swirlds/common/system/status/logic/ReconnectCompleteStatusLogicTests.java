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

package com.swirlds.common.system.status.logic;

import static com.swirlds.common.system.status.logic.StatusLogicTestUtils.triggerActionAndAssertException;
import static com.swirlds.common.system.status.logic.StatusLogicTestUtils.triggerActionAndAssertNoTransition;
import static com.swirlds.common.system.status.logic.StatusLogicTestUtils.triggerActionAndAssertTransition;

import com.swirlds.base.test.fixtures.FakeTime;
import com.swirlds.common.system.status.PlatformStatus;
import com.swirlds.common.system.status.PlatformStatusConfig;
import com.swirlds.common.system.status.actions.CatastrophicFailureAction;
import com.swirlds.common.system.status.actions.DoneReplayingEventsAction;
import com.swirlds.common.system.status.actions.FallenBehindAction;
import com.swirlds.common.system.status.actions.FreezePeriodEnteredAction;
import com.swirlds.common.system.status.actions.ReconnectCompleteAction;
import com.swirlds.common.system.status.actions.SelfEventReachedConsensusAction;
import com.swirlds.common.system.status.actions.StartedReplayingEventsAction;
import com.swirlds.common.system.status.actions.StateWrittenToDiskAction;
import com.swirlds.common.system.status.actions.TimeElapsedAction;
import com.swirlds.config.api.Configuration;
import com.swirlds.test.framework.config.TestConfigBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReconnectCompleteStatusLogic}.
 */
class ReconnectCompleteStatusLogicTests {
    private FakeTime time;
    private final long reconnectStateRound = 42L;
    private ReconnectCompleteStatusLogic logic;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        logic = new ReconnectCompleteStatusLogic(
                reconnectStateRound, null, configuration.getConfigData(PlatformStatusConfig.class));
    }

    @Test
    @DisplayName("Go to CHECKING when the round written precisely matches the reconnect state round")
    void toCheckingWithPreciseRoundMatch() {
        triggerActionAndAssertTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound),
                PlatformStatus.CHECKING);
    }

    @Test
    @DisplayName("Go to CHECKING when the round written doesn't precisely match the reconnect state round")
    void toCheckingWithImpreciseRoundMatch() {
        triggerActionAndAssertTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound + 3),
                PlatformStatus.CHECKING);
    }

    @Test
    @DisplayName("Go to BEHIND")
    void toBehind() {
        triggerActionAndAssertTransition(
                logic::processFallenBehindAction, new FallenBehindAction(), PlatformStatus.BEHIND);
    }

    @Test
    @DisplayName("Go to FREEZING when the round written precisely matches the reconnect state round")
    void toFreezingWithPreciseRoundMatch() {
        triggerActionAndAssertNoTransition(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), logic.getStatus());
        triggerActionAndAssertTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound),
                PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to FREEZING when the round written doesn't precisely match the reconnect state round")
    void toFreezingWithImpreciseRoundMatch() {
        triggerActionAndAssertNoTransition(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), logic.getStatus());
        triggerActionAndAssertTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound + 5),
                PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to FREEZING when the logic object was constructed with a non-null freeze boundary")
    void toFreezingWithPriorFreezeBoundary() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        // this logic is being constructed as if the freeze boundary was crossed while in the BEHIND status
        logic = new ReconnectCompleteStatusLogic(
                reconnectStateRound, 10L, configuration.getConfigData(PlatformStatusConfig.class));

        triggerActionAndAssertTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound),
                PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to CATASTROPHIC_FAILURE")
    void toCatastrophicFailure() {
        triggerActionAndAssertTransition(
                logic::processCatastrophicFailureAction,
                new CatastrophicFailureAction(),
                PlatformStatus.CATASTROPHIC_FAILURE);
    }

    @Test
    @DisplayName("Throw exception when receiving duplicate freeze round notification")
    void duplicateFreezeRound() {
        triggerActionAndAssertNoTransition(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), logic.getStatus());
        triggerActionAndAssertException(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), logic.getStatus());
    }

    @Test
    @DisplayName("Irrelevant actions shouldn't cause transitions")
    void irrelevantActions() {
        triggerActionAndAssertNoTransition(
                logic::processSelfEventReachedConsensusAction,
                new SelfEventReachedConsensusAction(time.now()),
                logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction, new TimeElapsedAction(time.now()), logic.getStatus());
        // if the state written is prior to the reconnect state, it should be ignored
        triggerActionAndAssertNoTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound - 1),
                logic.getStatus());
    }

    @Test
    @DisplayName("Unexpected actions should cause exceptions")
    void unexpectedActions() {
        triggerActionAndAssertException(
                logic::processStartedReplayingEventsAction, new StartedReplayingEventsAction(), logic.getStatus());
        triggerActionAndAssertException(
                logic::processDoneReplayingEventsAction, new DoneReplayingEventsAction(time.now()), logic.getStatus());
        triggerActionAndAssertException(
                logic::processReconnectCompleteAction, new ReconnectCompleteAction(0), logic.getStatus());
    }
}
