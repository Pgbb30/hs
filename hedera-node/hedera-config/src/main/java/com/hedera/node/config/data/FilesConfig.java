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

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("files")
public record FilesConfig(
        @ConfigProperty(defaultValue = "101") @NetworkProperty long addressBook,
        @ConfigProperty(defaultValue = "121") @NetworkProperty long networkProperties,
        @ConfigProperty(defaultValue = "112") @NetworkProperty long exchangeRates,
        @ConfigProperty(defaultValue = "111") @NetworkProperty long feeSchedules,
        @ConfigProperty(defaultValue = "122") @NetworkProperty long hapiPermissions,
        @ConfigProperty(defaultValue = "102") @NetworkProperty long nodeDetails,
        // @ConfigProperty(defaultValue = "150-159") Pair<Long, Long> softwareUpdateRange,
        @ConfigProperty(defaultValue = "123") @NetworkProperty long throttleDefinitions,
        @ConfigProperty(defaultValue = "1000000") @NetworkProperty long maxNumber,
        @ConfigProperty(defaultValue = "1024") @NetworkProperty int maxSizeKb) {}
