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

package com.swirlds.common.io.config;

import com.swirlds.common.config.StateConfig;
import com.swirlds.common.system.NodeId;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;

/**
 * Configuration for the {@link com.swirlds.common.io.utility.RecycleBin} class.
 *
 * @param recycleBinPath the directory where recycled files are stored, relative to the saved state directory defined by
 *                       {@link StateConfig#savedStateDirectory()}.
 */
@ConfigData("recycleBin")
public record RecycleBinConfig(@ConfigProperty(defaultValue = "swirlds-recycle-bin") String recycleBinPath) {

    /**
     * Returns the real path to the recycle bin.
     *
     * @param stateConfig the state config object
     * @param selfId      the ID of this node
     * @return the location where recycle bin files are stored
     */
    public Path getRecycleBinPath(@NonNull final StateConfig stateConfig, @NonNull final NodeId selfId) {
        return stateConfig.savedStateDirectory().resolve(recycleBinPath()).resolve(Long.toString(selfId.id()));
    }
}
