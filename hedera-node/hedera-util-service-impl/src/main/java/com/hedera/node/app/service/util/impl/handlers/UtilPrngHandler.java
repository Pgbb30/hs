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

package com.hedera.node.app.service.util.impl.handlers;

import static java.util.Objects.requireNonNull;

import com.google.common.math.IntMath;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.util.impl.records.PrngRecordBuilder;
import com.hedera.node.app.spi.workflows.*;
import com.hedera.node.config.data.UtilPrngConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.RunningHash;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#UTIL_PRNG}.
 */
@Singleton
public class UtilPrngHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(UtilPrngHandler.class);
    public static final byte[] MISSING_BYTES = new byte[0];

    @Inject
    public UtilPrngHandler() {
        // Dagger2
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        // validate range is greater than zero
        if (context.body().utilPrngOrThrow().range() < 0) {
            throw new PreCheckException(ResponseCodeEnum.INVALID_PRNG_RANGE);
        }
        // Payer key is fetched in PreHandleWorkflow
    }

    /**
     * {@inheritDoc}
     */
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context);

        final var op = context.body().utilPrngOrThrow();
        final var range = op.range();

        // TODO: This check should probably be moved into app
        final var config = context.configuration().getConfigData(UtilPrngConfig.class);
        if (!config.isEnabled()) {
            return;
        }
        // get the n-3 running hash. If the running hash is not available, will throw a
        // HandleException
        final Bytes pseudoRandomBytes = context.blockRecordInfo().getNMinus3RunningHash();

        // If no bytes are available then return
        if (pseudoRandomBytes == null || pseudoRandomBytes.length() == 0) {
            return;
        }
        // If range is provided then generate a random number in the given range
        // from the pseudoRandomBytes
        final var recordBuilder = context.recordBuilder(PrngRecordBuilder.class);
        if (range > 0) {
            final int pseudoRandomNumber = randomNumFromBytes(pseudoRandomBytes.toByteArray(), range);
            recordBuilder.entropyNumber(pseudoRandomNumber);
        } else {
            recordBuilder.entropyBytes(pseudoRandomBytes);
        }
    }

    /**
     * Get the n-3 running hash bytes from the n-3 running hash. If the running hash is not
     * available, will throw a HandleException. n-3 running hash is chosen for generating random
     * number instead of n-1 running hash for processing transactions quickly. Because n-1 running
     * hash might not be available when the transaction is processed and might need to wait longer.
     *
     * @param nMinus3RunningHash n-3 running hash
     * @return n-3 running hash bytes
     */
    private byte[] getNMinus3RunningHashBytes(@NonNull final RunningHash nMinus3RunningHash) {
        // This can't happen because this running hash is taken from record stream.
        // If this happens then there is a bug in the code.
        requireNonNull(nMinus3RunningHash);

        Hash nMinusThreeHash;
        try {
            nMinusThreeHash = nMinus3RunningHash.getFutureHash().get();
            // Use n-3 running hash instead of n-1 running hash for processing transactions quickly
            if (nMinusThreeHash == null || Arrays.equals(nMinusThreeHash.getValue(), new byte[48])) {
                log.info("No n-3 record running hash available to generate random number");
                return MISSING_BYTES;
            }
            // generate binary string from the running hash of records
            return nMinusThreeHash.getValue();
        } catch (InterruptedException e) {
            log.error("Interrupted exception while waiting for n-3 running hash", e);
            Thread.currentThread().interrupt();
            throw new HandleException(ResponseCodeEnum.UNKNOWN);
            // FUTURE : Need to decide on the response code for this case
        } catch (ExecutionException e) {
            log.error("Unable to get current n-3 running hash", e);
            throw new HandleException(ResponseCodeEnum.UNKNOWN);
            // FUTURE : Need to decide on the response code for this case
        }
    }

    /**
     * Generate a random number from the given bytes in the given range
     * @param pseudoRandomBytes bytes to generate random number from
     * @param range range of the random number
     * @return random number
     */
    private int randomNumFromBytes(@NonNull final byte[] pseudoRandomBytes, final int range) {
        final int initialBitsValue = ByteBuffer.wrap(pseudoRandomBytes, 0, 4).getInt();
        return IntMath.mod(initialBitsValue, range);
    }
}
