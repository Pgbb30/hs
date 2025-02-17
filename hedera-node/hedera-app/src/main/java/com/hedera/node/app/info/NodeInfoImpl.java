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

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.spi.info.NodeInfo;
import com.swirlds.common.system.address.Address;
import edu.umd.cs.findbugs.annotations.NonNull;

public record NodeInfoImpl(long nodeId, @NonNull AccountID accountId, boolean zeroStake, @NonNull String memo)
        implements NodeInfo {
    @NonNull
    static NodeInfo fromAddress(@NonNull final Address address) {
        return new NodeInfoImpl(
                address.getNodeId().id(), parseAccount(address.getMemo()), address.getWeight() <= 0, address.getMemo());
    }
}
