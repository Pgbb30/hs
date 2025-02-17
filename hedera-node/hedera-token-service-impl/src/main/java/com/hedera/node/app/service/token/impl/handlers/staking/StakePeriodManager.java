/*
 * Copyright (C) 2022-2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.token.impl.handlers.staking;

import static com.hedera.node.app.service.mono.utils.Units.MINUTES_TO_MILLISECONDS;
import static com.swirlds.common.stream.LinkedObjectStreamUtilities.getPeriod;
import static com.swirlds.common.utility.Units.MINUTES_TO_SECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.StakingConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class manages the current stake period and the previous stake period.
 */
@Singleton
public class StakePeriodManager {
    // Sentinel value for a field that wasn't applicable to this transaction
    public static final long NA = Long.MIN_VALUE;
    public static final ZoneId ZONE_UTC = ZoneId.of("UTC");
    public static final long DEFAULT_STAKING_PERIOD_MINS = 1440L;

    private final int numStoredPeriods;
    private final long stakingPeriodMins;
    private long currentStakePeriod;
    private long prevConsensusSecs;

    @Inject
    public StakePeriodManager(@NonNull final ConfigProvider configProvider) {
        final var config = configProvider.getConfiguration().getConfigData(StakingConfig.class);
        numStoredPeriods = config.rewardHistoryNumStoredPeriods();
        stakingPeriodMins = config.periodMins();
    }

    /**
     * Returns the epoch second at the start of the given stake period. It is used in
     * {@link com.hedera.node.app.service.token.impl.utils.RewardCalculator} to set the stakePeriodStart
     * on each {@link com.hedera.hapi.node.state.token.StakingNodeInfo} object
     * @param stakePeriod the stake period
     * @return the epoch second at the start of the given stake period
     */
    public long epochSecondAtStartOfPeriod(final long stakePeriod) {
        if (stakingPeriodMins == DEFAULT_STAKING_PERIOD_MINS) {
            return LocalDate.ofEpochDay(stakePeriod).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        } else {
            return stakePeriod * stakingPeriodMins * MINUTES_TO_SECONDS;
        }
    }

    /**
     * Returns the current stake period, based on the current consensus time.
     * Current staking period is very important to calculate rewards.
     * Since any account is rewarded only once per a stake period.
     * @param consensusNow the current consensus time
     * @return the current stake period
     */
    public long currentStakePeriod(@NonNull final Instant consensusNow) {
        final var currentConsensusSecs = consensusNow.getEpochSecond();
        if (prevConsensusSecs != currentConsensusSecs) {
            if (stakingPeriodMins == DEFAULT_STAKING_PERIOD_MINS) {
                currentStakePeriod = LocalDate.ofInstant(consensusNow, ZONE_UTC).toEpochDay();
            } else {
                currentStakePeriod = getPeriod(consensusNow, stakingPeriodMins * MINUTES_TO_MILLISECONDS);
            }
            prevConsensusSecs = currentConsensusSecs;
        }
        return currentStakePeriod;
    }

    /**
     * Based on the stake period start, returns if the current consensus time is
     * rewardable or not.
     * @param stakePeriodStart the stake period start
     * @param networkRewards the network rewards
     * @param consensusNow the current consensus time
     * @return true if the current consensus time is rewardable, false otherwise
     */
    public boolean isRewardable(
            final long stakePeriodStart,
            @NonNull final ReadableNetworkStakingRewardsStore networkRewards,
            @NonNull final Instant consensusNow) {
        return stakePeriodStart > -1 && stakePeriodStart < firstNonRewardableStakePeriod(networkRewards, consensusNow);
    }

    /**
     * Returns the first stake period that is not rewardable. This is used to determine
     * if an account is eligible for a reward, as soon as staking rewards are activated.
     * @param networkRewards the network rewards
     * @param consensusNow the current consensus time
     * @return the first stake period that is not rewardable
     */
    public long firstNonRewardableStakePeriod(
            @NonNull final ReadableNetworkStakingRewardsStore networkRewards, @NonNull final Instant consensusNow) {

        // The earliest period by which an account can have started staking, _without_ becoming
        // eligible for a reward; if staking is not active, this will return Long.MIN_VALUE so
        // no account can ever be eligible.
        return networkRewards.isStakingRewardsActivated() ? currentStakePeriod(consensusNow) - 1 : Long.MIN_VALUE;
    }

    /**
     * Returns the effective stake period start, based on the current stake period and the
     * number of stored periods.
     * @param stakePeriodStart the stake period start
     * @return the effective stake period start
     */
    public long effectivePeriod(final long stakePeriodStart) {
        if (stakePeriodStart > -1 && stakePeriodStart < currentStakePeriod - numStoredPeriods) {
            return currentStakePeriod - numStoredPeriods;
        }
        return stakePeriodStart;
    }

    /* ----------------------- estimated stake periods ----------------------- */
    /**
     * Returns the estimated current stake period, based on the current wall-clock time.
     * We use wall-clock time here, because this method is called in two places:
     * 1. When we get the first stakePeriod after staking rewards are activated, to see
     *    if any rewards can be triggered.
     * 2. When we do upgrade, if we need to migrate any staking rewards.
     * The default staking period is 1 day, so this will return the current day.
     * For testing we use a shorter staking period, so we can estimate staking period for
     * a shorter period.
     * @return the estimated current stake period
     */
    public long estimatedCurrentStakePeriod() {
        final var now = Instant.now();
        if (stakingPeriodMins == DEFAULT_STAKING_PERIOD_MINS) {
            return LocalDate.ofInstant(now, ZONE_UTC).toEpochDay();
        } else {
            return getPeriod(now, stakingPeriodMins * MINUTES_TO_MILLISECONDS);
        }
    }

    /**
     * Returns the estimated first stake period that is not rewardable.
     * @param networkRewards the network rewards
     * @return the estimated first stake period that is not rewardable
     */
    public long estimatedFirstNonRewardableStakePeriod(
            @NonNull final ReadableNetworkStakingRewardsStore networkRewards) {
        return networkRewards.isStakingRewardsActivated() ? estimatedCurrentStakePeriod() - 1 : Long.MIN_VALUE;
    }

    /**
     * Returns if the estimated stake period is rewardable or not.
     * @param stakePeriodStart the stake period start
     * @param networkRewards the network rewards
     * @return true if the estimated stake period is rewardable, false otherwise
     */
    public boolean isEstimatedRewardable(
            final long stakePeriodStart, @NonNull final ReadableNetworkStakingRewardsStore networkRewards) {
        return stakePeriodStart > -1 && stakePeriodStart < estimatedFirstNonRewardableStakePeriod(networkRewards);
    }

    @VisibleForTesting
    public long getPrevConsensusSecs() {
        return prevConsensusSecs;
    }
}
