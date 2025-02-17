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

package com.hedera.node.config.data;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("staking")
public record StakingConfig(
        @ConfigProperty(defaultValue = "1440") long periodMins,
        @ConfigProperty(value = "rewardHistory.numStoredPeriods", defaultValue = "365")
                int rewardHistoryNumStoredPeriods,
        // ConfigProperty(value = "startupHelper.recompute", defaultValue = "NODE_STAKES,PENDING_REWARDS")
        // Set<StakeStartupHelper.RecomputeType> startupHelperRecompute
        @ConfigProperty(value = "fees.nodeRewardPercentage", defaultValue = "0") int feesNodeRewardPercentage,
        @ConfigProperty(value = "fees.stakingRewardPercentage", defaultValue = "0") int feesStakingRewardPercentage,
        // @ConfigProperty(defaultValue = "") Map<Long, Long> nodeMaxToMinStakeRatios,
        @ConfigProperty(defaultValue = "true") boolean isEnabled,
        @ConfigProperty(defaultValue = "17808") long maxDailyStakeRewardThPerH,
        @ConfigProperty(defaultValue = "false") boolean requireMinStakeToReward,
        @ConfigProperty(defaultValue = "0") long rewardRate,
        @ConfigProperty(defaultValue = "25000000000000000") long startThreshold,
        @ConfigProperty(defaultValue = "500") int sumOfConsensusWeights,
        @ConfigProperty(defaultValue = "0") long stakingRewardBalanceThreshold,
        @ConfigProperty(defaultValue = "5000000000000000000") long stakingMaxStakeRewarded) {}
