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

package com.swirlds.common.system.status;

import static com.swirlds.logging.LogMarker.EXCEPTION;
import static com.swirlds.logging.LogMarker.PLATFORM_STATUS;

import com.swirlds.base.time.Time;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.notification.listeners.PlatformStatusChangeListener;
import com.swirlds.common.notification.listeners.PlatformStatusChangeNotification;
import com.swirlds.common.system.status.actions.CatastrophicFailureAction;
import com.swirlds.common.system.status.actions.DoneReplayingEventsAction;
import com.swirlds.common.system.status.actions.FallenBehindAction;
import com.swirlds.common.system.status.actions.FreezePeriodEnteredAction;
import com.swirlds.common.system.status.actions.PlatformStatusAction;
import com.swirlds.common.system.status.actions.ReconnectCompleteAction;
import com.swirlds.common.system.status.actions.SelfEventReachedConsensusAction;
import com.swirlds.common.system.status.actions.StartedReplayingEventsAction;
import com.swirlds.common.system.status.actions.StateWrittenToDiskAction;
import com.swirlds.common.system.status.actions.TimeElapsedAction;
import com.swirlds.common.system.status.logic.PlatformStatusLogic;
import com.swirlds.common.system.status.logic.StartingUpStatusLogic;
import com.swirlds.logging.payloads.PlatformStatusPayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The platform status state machine
 */
public class PlatformStatusStateMachine {
    private static final Logger logger = LogManager.getLogger(PlatformStatusStateMachine.class);

    /**
     * A source of time
     */
    private final Time time;

    /**
     * For passing notifications between the platform and the application.
     */
    private final NotificationEngine notificationEngine;

    /**
     * The object containing the state machine logic for the current status
     */
    private PlatformStatusLogic currentStatusLogic;

    /**
     * The time at which the current status started
     */
    private Instant currentStatusStartTime;

    /**
     * Constructor
     *
     * @param time               a source of time
     * @param config             the platform status config
     * @param notificationEngine the notification engine
     */
    public PlatformStatusStateMachine(
            @NonNull final Time time,
            @NonNull PlatformStatusConfig config,
            @NonNull final NotificationEngine notificationEngine) {

        this.time = Objects.requireNonNull(time);
        this.notificationEngine = Objects.requireNonNull(notificationEngine);
        this.currentStatusLogic = new StartingUpStatusLogic(config);
        this.currentStatusStartTime = time.now();
    }

    /**
     * Passes the received action into the logic method that corresponds with the action type, and returns whatever that
     * logic method returns.
     * <p>
     * If the logic method throws an {@link IllegalPlatformStatusException}, this method will log the exception and
     * return null.
     *
     * @param action the action to process
     * @return a new logic object, or null if the logic method threw an exception
     */
    @Nullable
    private PlatformStatusLogic getNewLogic(@NonNull final PlatformStatusAction action) {
        Objects.requireNonNull(action);

        final Class<? extends PlatformStatusAction> actionClass = action.getClass();

        try {
            if (actionClass == CatastrophicFailureAction.class) {
                return currentStatusLogic.processCatastrophicFailureAction((CatastrophicFailureAction) action);
            } else if (actionClass == DoneReplayingEventsAction.class) {
                return currentStatusLogic.processDoneReplayingEventsAction((DoneReplayingEventsAction) action);
            } else if (actionClass == FallenBehindAction.class) {
                return currentStatusLogic.processFallenBehindAction((FallenBehindAction) action);
            } else if (actionClass == FreezePeriodEnteredAction.class) {
                return currentStatusLogic.processFreezePeriodEnteredAction((FreezePeriodEnteredAction) action);
            } else if (actionClass == ReconnectCompleteAction.class) {
                return currentStatusLogic.processReconnectCompleteAction((ReconnectCompleteAction) action);
            } else if (actionClass == SelfEventReachedConsensusAction.class) {
                return currentStatusLogic.processSelfEventReachedConsensusAction(
                        (SelfEventReachedConsensusAction) action);
            } else if (actionClass == StartedReplayingEventsAction.class) {
                return currentStatusLogic.processStartedReplayingEventsAction((StartedReplayingEventsAction) action);
            } else if (actionClass == StateWrittenToDiskAction.class) {
                return currentStatusLogic.processStateWrittenToDiskAction((StateWrittenToDiskAction) action);
            } else if (actionClass == TimeElapsedAction.class) {
                return currentStatusLogic.processTimeElapsedAction((TimeElapsedAction) action);
            } else {
                throw new IllegalArgumentException(
                        "Unknown action type: " + action.getClass().getName());
            }
        } catch (final IllegalPlatformStatusException e) {
            logger.error(EXCEPTION.getMarker(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Process a platform status action.
     * <p>
     * Repeated calls of this method cause the platform state machine to be traversed
     *
     * @param action the action to process
     */
    public void processStatusAction(@NonNull final PlatformStatusAction action) {
        Objects.requireNonNull(action);

        final PlatformStatusLogic newLogic = getNewLogic(action);

        if (newLogic == null || newLogic == currentStatusLogic) {
            // if status didn't change, there isn't anything to do
            return;
        }

        if (logger.isInfoEnabled()) {
            final String previousStatusName = currentStatusLogic.getStatus().name();
            final String newStatusName = newLogic.getStatus().name();

            final String statusChangeMessage = "Platform spent %s time in %s. Now in %s"
                    .formatted(Duration.between(currentStatusStartTime, time.now()), previousStatusName, newStatusName);

            logger.info(
                    PLATFORM_STATUS.getMarker(),
                    () -> new PlatformStatusPayload(statusChangeMessage, previousStatusName, newStatusName).toString());
        }

        notificationEngine.dispatch(
                PlatformStatusChangeListener.class, new PlatformStatusChangeNotification(newLogic.getStatus()));

        currentStatusLogic = newLogic;
        currentStatusStartTime = time.now();
    }

    /**
     * Get the current platform status
     *
     * @return the current platform status
     */
    public PlatformStatus getCurrentStatus() {
        return currentStatusLogic.getStatus();
    }
}
