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

package com.hedera.node.app.service.contract.impl.hevm;

import com.hedera.node.app.service.evm.contracts.execution.BlockMetaSource;
import com.hedera.node.app.service.evm.contracts.execution.HederaEvmTxProcessor;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.ImmutableHash;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.frame.BlockValues;

/**
 * Provides block information as context for a {@link HederaEvmTxProcessor}.
 */
public interface HederaEvmBlocks {
    Hash UNAVAILABLE_BLOCK_HASH = besuHashFrom(new ImmutableHash(new byte[DigestType.SHA_384.digestLength()]));

    /**
     * Returns the hash of the given block number, or {@link BlockMetaSource#UNAVAILABLE_BLOCK_HASH}
     * if unavailable.
     *
     * @param blockNo the block number of interest
     * @return its hash, if available
     */
    Hash blockHashOf(long blockNo);

    /**
     * Returns the in-scope block values, given an effective gas limit.
     *
     * @param gasLimit the effective gas limit
     * @return the scoped block values
     */
    BlockValues blockValuesOf(long gasLimit);

    static Hash besuHashFrom(@NonNull final com.swirlds.common.crypto.Hash hash) {
        final byte[] hashBytesToConvert = hash.getValue();
        final byte[] prefixBytes = new byte[32];
        System.arraycopy(hashBytesToConvert, 0, prefixBytes, 0, 32);
        return org.hyperledger.besu.datatypes.Hash.wrap(Bytes32.wrap(prefixBytes));
    }
}
