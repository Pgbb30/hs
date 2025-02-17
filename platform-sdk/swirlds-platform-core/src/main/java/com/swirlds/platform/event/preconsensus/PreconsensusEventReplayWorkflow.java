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

package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.formatting.StringFormattingUtils.commaSeparatedNumber;
import static com.swirlds.common.system.status.PlatformStatus.FREEZE_COMPLETE;
import static com.swirlds.common.system.status.PlatformStatus.FREEZING;
import static com.swirlds.common.system.status.PlatformStatus.OBSERVING;
import static com.swirlds.common.system.status.PlatformStatus.REPLAYING_EVENTS;
import static com.swirlds.common.system.status.PlatformStatus.STARTING_UP;
import static com.swirlds.common.units.TimeUnit.UNIT_MILLISECONDS;
import static com.swirlds.logging.LogMarker.EXCEPTION;
import static com.swirlds.logging.LogMarker.STARTUP;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.formatting.UnitFormatter;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.system.status.PlatformStatus;
import com.swirlds.common.threading.framework.QueueThread;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.platform.components.EventTaskDispatcher;
import com.swirlds.platform.components.state.StateManagementComponent;
import com.swirlds.platform.event.EventIntakeTask;
import com.swirlds.platform.eventhandling.ConsensusRoundHandler;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class encapsulates the logic for replaying preconsensus events at boot up time.
 */
public final class PreconsensusEventReplayWorkflow {

    private static final Logger logger = LogManager.getLogger(PreconsensusEventReplayWorkflow.class);

    private PreconsensusEventReplayWorkflow() {}

    /**
     * Replays preconsensus events from disk.
     *
     * @param platformContext                    the platform context for this node
     * @param threadManager                      the thread manager for this node
     * @param preconsensusEventFileManager       manages the preconsensus event files on disk
     * @param preconsensusEventWriter            writes preconsensus events to disk
     * @param eventTaskDispatcher                where events read from the stream should be passed
     * @param intakeQueue                        the queue thread for the event intake component
     * @param consensusRoundHandler              the object responsible for applying transactions to consensus rounds
     * @param stateHashSignQueue                 the queue thread for hashing and signing states
     * @param stateManagementComponent           manages various copies of the state
     * @param getPlatformStatus                  used to get the current platform status
     * @param setPlatformStatus                  used to set the current platform status
     * @param initialMinimumGenerationNonAncient the minimum generation of events to replay
     */
    public static void replayPreconsensusEvents(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final Time time,
            @NonNull final PreconsensusEventFileManager preconsensusEventFileManager,
            @NonNull final PreconsensusEventWriter preconsensusEventWriter,
            @NonNull final EventTaskDispatcher eventTaskDispatcher,
            @NonNull final QueueThread<EventIntakeTask> intakeQueue,
            @NonNull final ConsensusRoundHandler consensusRoundHandler,
            @NonNull final QueueThread<ReservedSignedState> stateHashSignQueue,
            @NonNull final StateManagementComponent stateManagementComponent,
            @NonNull final Supplier<PlatformStatus> getPlatformStatus,
            @NonNull final Consumer<PlatformStatus> setPlatformStatus,
            final long initialMinimumGenerationNonAncient) {

        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(threadManager);
        Objects.requireNonNull(time);
        Objects.requireNonNull(preconsensusEventFileManager);
        Objects.requireNonNull(preconsensusEventWriter);
        Objects.requireNonNull(eventTaskDispatcher);
        Objects.requireNonNull(intakeQueue);
        Objects.requireNonNull(consensusRoundHandler);
        Objects.requireNonNull(stateHashSignQueue);
        Objects.requireNonNull(stateManagementComponent);
        Objects.requireNonNull(getPlatformStatus);
        Objects.requireNonNull(setPlatformStatus);

        setupReplayStatus(getPlatformStatus, setPlatformStatus);

        logger.info(
                STARTUP.getMarker(),
                "replaying preconsensus event stream starting at generation {}",
                initialMinimumGenerationNonAncient);

        try {
            final Instant start = time.now();

            final IOIterator<EventImpl> iterator =
                    preconsensusEventFileManager.getEventIterator(initialMinimumGenerationNonAncient, true);

            final PreconsensusEventReplayPipeline eventReplayPipeline = new PreconsensusEventReplayPipeline(
                    platformContext, threadManager, iterator, eventTaskDispatcher::dispatchTask);
            eventReplayPipeline.replayEvents();

            waitForReplayToComplete(intakeQueue, consensusRoundHandler, stateHashSignQueue);

            final Instant finish = time.now();
            final Duration elapsed = Duration.between(start, finish);

            logReplayInfo(
                    stateManagementComponent,
                    eventReplayPipeline.getEventCount(),
                    eventReplayPipeline.getTransactionCount(),
                    elapsed);

            preconsensusEventWriter.beginStreamingNewEvents();

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while replaying preconsensus event stream", e);
        }

        setupEndOfReplayStatus(getPlatformStatus, setPlatformStatus);
    }

    /**
     * Update the platform status for PCES replay.
     */
    private static void setupReplayStatus(
            @NonNull final Supplier<PlatformStatus> getPlatformStatus,
            @NonNull final Consumer<PlatformStatus> setPlatformStatus) {

        // Sanity check for platform status can be removed after we clean up platform status management
        final PlatformStatus currentPlatformStatus = getPlatformStatus.get();
        if (currentPlatformStatus != STARTING_UP) {
            throw new IllegalStateException(
                    "Platform status should be STARTING_UP, current status is " + currentPlatformStatus);
        }

        setPlatformStatus.accept(REPLAYING_EVENTS);
    }

    /**
     * Update the platform status to indicate that PCES replay has completed.
     */
    private static void setupEndOfReplayStatus(
            @NonNull final Supplier<PlatformStatus> getPlatformStatus,
            @NonNull final Consumer<PlatformStatus> setPlatformStatus) {

        // Sanity check for platform status can be removed after we clean up platform status management
        final PlatformStatus currentPlatformStatus = getPlatformStatus.get();

        if (currentPlatformStatus == FREEZING || currentPlatformStatus == FREEZE_COMPLETE) {
            logger.info(STARTUP.getMarker(), "Freeze boundary crossed while replaying the preconsensus event stream.");
            return;
        }

        if (currentPlatformStatus != REPLAYING_EVENTS) {
            throw new IllegalStateException(
                    "Platform status should be REPLAYING_EVENTS, current status is " + currentPlatformStatus);
        }
        setPlatformStatus.accept(OBSERVING);
    }

    /**
     * Wait for all events to be replayed. Some of this work happens on asynchronous threads, so we need to wait for them
     * to complete even after we exhaust all available events from the stream.
     */
    private static void waitForReplayToComplete(
            @NonNull final QueueThread<EventIntakeTask> intakeQueue,
            @NonNull final ConsensusRoundHandler consensusRoundHandler,
            @NonNull final QueueThread<ReservedSignedState> stateHashSignQueue)
            throws InterruptedException {

        // Wait until all events from the preconsensus event stream have been fully ingested.
        intakeQueue.waitUntilNotBusy();

        // Wait until all rounds from the preconsensus event stream have been fully processed.
        consensusRoundHandler.waitUntilNotBusy();

        // Wait until we have hashed/signed all rounds
        stateHashSignQueue.waitUntilNotBusy();
    }

    /**
     * Write information about the replay to disk.
     */
    private static void logReplayInfo(
            @NonNull final StateManagementComponent stateManagementComponent,
            final long eventCount,
            final long transactionCount,
            @NonNull final Duration elapsedTime) {

        try (final ReservedSignedState latestConsensusRound =
                stateManagementComponent.getLatestImmutableState("SwirldsPlatform.replayPreconsensusEventStream()")) {

            if (latestConsensusRound.isNull()) {
                logger.info(
                        STARTUP.getMarker(),
                        "Replayed {} preconsensus events. No rounds reached consensus.",
                        commaSeparatedNumber(eventCount));
                return;
            }

            final Instant firstTimestamp = stateManagementComponent.getFirstStateTimestamp();
            final long firstRound = stateManagementComponent.getFirstStateRound();

            if (firstTimestamp == null) {
                // This should be impossible. If we have a state, we should have a timestamp.
                logger.error(
                        EXCEPTION.getMarker(),
                        "Replayed {} preconsensus events. "
                                + "First state timestamp is null, which should not be possible if a "
                                + "round has reached consensus",
                        commaSeparatedNumber(eventCount));
                return;
            }

            final long latestRound = latestConsensusRound.get().getRound();
            final long elapsedRounds = latestRound - firstRound;

            final Instant latestRoundTimestamp = latestConsensusRound.get().getConsensusTimestamp();
            final Duration elapsedConsensusTime = Duration.between(firstTimestamp, latestRoundTimestamp);

            logger.info(
                    STARTUP.getMarker(),
                    "replayed {} preconsensus events. These events contained {} transactions. "
                            + "{} rounds reached consensus spanning {} of consensus time. The latest "
                            + "round to reach consensus is round {}. Replay took {}.",
                    commaSeparatedNumber(eventCount),
                    commaSeparatedNumber(transactionCount),
                    commaSeparatedNumber(elapsedRounds),
                    new UnitFormatter(elapsedConsensusTime.toMillis(), UNIT_MILLISECONDS)
                            .setAbbreviate(false)
                            .render(),
                    commaSeparatedNumber(latestRound),
                    new UnitFormatter(elapsedTime.toMillis(), UNIT_MILLISECONDS)
                            .setAbbreviate(false)
                            .render());
        }
    }
}
