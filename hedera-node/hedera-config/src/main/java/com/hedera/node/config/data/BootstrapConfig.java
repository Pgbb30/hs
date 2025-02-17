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

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("bootstrap")
public record BootstrapConfig(
        @ConfigProperty(value = "feeSchedulesJson.resource", defaultValue = "feeSchedules.json")
                String feeSchedulesJsonResource,
        @ConfigProperty(
                        value = "genesisPublicKey",
                        defaultValue = "0aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110e92")
                Bytes genesisPublicKey,
        @ConfigProperty(value = "hapiPermissions.path", defaultValue = "data/config/api-permission.properties")
                String hapiPermissionsPath,
        @ConfigProperty(value = "networkProperties.path", defaultValue = "data/config/application.properties")
                String networkPropertiesPath,
        @ConfigProperty(value = "rates.currentHbarEquiv", defaultValue = "1") int ratesCurrentHbarEquiv,
        @ConfigProperty(value = "rates.currentCentEquiv", defaultValue = "12") int ratesCurrentCentEquiv,
        @ConfigProperty(value = "rates.currentExpiry", defaultValue = "4102444800") long ratesCurrentExpiry,
        @ConfigProperty(value = "rates.nextHbarEquiv", defaultValue = "1") int ratesNextHbarEquiv,
        @ConfigProperty(value = "rates.nextCentEquiv", defaultValue = "15") int ratesNextCentEquiv,
        @ConfigProperty(value = "rates.nextExpiry", defaultValue = "4102444800") long ratesNextExpiry,
        @ConfigProperty(value = "system.entityExpiry", defaultValue = "1812637686") long systemEntityExpiry,
        @ConfigProperty(value = "throttleDefsJson.resource", defaultValue = "throttles.json")
                String throttleDefsJsonResource) {}
