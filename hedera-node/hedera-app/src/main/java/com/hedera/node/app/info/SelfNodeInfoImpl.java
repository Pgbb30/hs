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

package com.hedera.node.app.info;

import static com.hedera.node.app.spi.HapiUtils.parseAccount;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.spi.info.SelfNodeInfo;
import com.hedera.node.app.version.HederaSoftwareVersion;
import com.swirlds.common.system.address.Address;
import edu.umd.cs.findbugs.annotations.NonNull;

public record SelfNodeInfoImpl(
        long nodeId,
        @NonNull AccountID accountId,
        boolean zeroStake,
        @NonNull String memo,
        @NonNull HederaSoftwareVersion version)
        implements SelfNodeInfo {

    public SelfNodeInfoImpl {
        requireNonNull(accountId);
        requireNonNull(memo);
        requireNonNull(version);
        if (nodeId < 0) {
            throw new IllegalArgumentException("node ID cannot be less than 0");
        }
    }

    @NonNull
    public static SelfNodeInfo of(@NonNull final Address address, @NonNull final HederaSoftwareVersion version) {
        return new SelfNodeInfoImpl(
                address.getNodeId().id(),
                parseAccount(address.getMemo()),
                address.getWeight() <= 0,
                address.getMemo(),
                version);
    }

    @NonNull
    @Override
    public SemanticVersion hapiVersion() {
        return version.getHapiVersion();
    }

    @NonNull
    @Override
    public SemanticVersion appVersion() {
        return version.getServicesVersion();
    }
}
