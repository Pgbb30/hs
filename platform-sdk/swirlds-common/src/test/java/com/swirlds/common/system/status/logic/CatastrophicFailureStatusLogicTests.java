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

import static com.swirlds.common.system.status.logic.StatusLogicTestUtils.triggerActionAndAssertNoTransition;

import com.swirlds.base.test.fixtures.FakeTime;
import com.swirlds.common.system.status.actions.CatastrophicFailureAction;
import com.swirlds.common.system.status.actions.DoneReplayingEventsAction;
import com.swirlds.common.system.status.actions.FallenBehindAction;
import com.swirlds.common.system.status.actions.FreezePeriodEnteredAction;
import com.swirlds.common.system.status.actions.ReconnectCompleteAction;
import com.swirlds.common.system.status.actions.SelfEventReachedConsensusAction;
import com.swirlds.common.system.status.actions.StartedReplayingEventsAction;
import com.swirlds.common.system.status.actions.StateWrittenToDiskAction;
import com.swirlds.common.system.status.actions.TimeElapsedAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CatastrophicFailureStatusLogic}.
 */
class CatastrophicFailureStatusLogicTests {
    private FakeTime time;
    private CatastrophicFailureStatusLogic logic;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        logic = new CatastrophicFailureStatusLogic();
    }

    @Test
    @DisplayName("Irrelevant actions shouldn't cause transitions")
    void irrelevantActions() {
        triggerActionAndAssertNoTransition(
                logic::processStartedReplayingEventsAction, new StartedReplayingEventsAction(), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processDoneReplayingEventsAction, new DoneReplayingEventsAction(time.now()), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processSelfEventReachedConsensusAction,
                new SelfEventReachedConsensusAction(time.now()),
                logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processFallenBehindAction, new FallenBehindAction(), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processReconnectCompleteAction, new ReconnectCompleteAction(0), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processStateWrittenToDiskAction, new StateWrittenToDiskAction(0), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processCatastrophicFailureAction, new CatastrophicFailureAction(), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction, new TimeElapsedAction(time.now()), logic.getStatus());
    }
}
