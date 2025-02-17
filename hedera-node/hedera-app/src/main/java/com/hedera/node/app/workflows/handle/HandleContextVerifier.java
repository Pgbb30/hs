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

package com.hedera.node.app.workflows.handle;

import static com.hedera.node.app.spi.signatures.SignatureVerification.failedVerification;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class that contains all functionality for verifying signatures during handle.
 */
public class HandleContextVerifier {

    private static final Logger logger = LoggerFactory.getLogger(HandleContextVerifier.class);

    private final long timeout;
    private final Map<Key, SignatureVerificationFuture> keyVerifications;

    /**
     * Creates a {@link HandleContextVerifier}
     *
     * @param keyVerifications A {@link Map} with all data to verify signatures
     */
    public HandleContextVerifier(
            @NonNull final HederaConfig config, @NonNull final Map<Key, SignatureVerificationFuture> keyVerifications) {
        this.timeout = requireNonNull(config, "config must not be null").workflowVerificationTimeoutMS();
        this.keyVerifications = requireNonNull(keyVerifications, "keyVerifications must not be null");
    }

    /**
     * Get a {@link SignatureVerification} for the given key.
     *
     * <p>If the key is a cryptographic key (i.e. a basic key like ED25519 or ECDSA_SECP256K1), and the cryptographic
     * key was in the signature map of the transaction, then a {@link SignatureVerification} will be for that key.
     * If there was no such cryptographic key in the signature map, {@code null} is returned.
     *
     * <p>If the key is a key list, then a {@link SignatureVerification} will be returned that aggregates the results
     * of each key in the key list, possibly nested.
     *
     * <p>If the key is a threshold key, then a {@link SignatureVerification} will be returned that aggregates the
     * results of each key in the threshold key, possibly nested, based on the threshold for that key.
     *
     * @param key The key to check on the verification results for.
     * @return A {@link SignatureVerification} for the given key, if available, {@code null} otherwise.
     */
    @NonNull
    public SignatureVerification verificationFor(@NonNull final Key key) {
        requireNonNull(key, "key must not be null");
        // FUTURE: Cache the results of this method, if it is usually called several times
        return resolveFuture(verificationFutureFor(key), () -> failedVerification(key));
    }

    /**
     * Look for a {@link SignatureVerification} that applies to the given hollow account.
     * @param evmAlias The evm alias to lookup verification for.
     * @return The {@link SignatureVerification} for the given hollow account.
     */
    @NonNull
    public SignatureVerification verificationFor(@NonNull final Bytes evmAlias) {
        requireNonNull(evmAlias, "evmAlias must not be null");
        // FUTURE: Cache the results of this method, if it is usually called several times
        if (evmAlias.length() == 20) {
            for (final var result : keyVerifications.values()) {
                final var account = result.evmAlias();
                if (account != null && evmAlias.matchesPrefix(account)) {
                    return resolveFuture(result, () -> failedVerification(evmAlias));
                }
            }
        }
        return failedVerification(evmAlias);
    }

    /**
     * Get a {@link Future<SignatureVerification>} for the given key.
     *
     * <p>If the key is a cryptographic key (i.e. a basic key like ED25519 or ECDSA_SECP256K1), and the cryptographic
     * key was in the signature map of the transaction, then a {@link Future} will be returned that will yield the
     * {@link SignatureVerification} for that key. If there was no such cryptographic key in the signature map, then
     * a completed, failed future is returned.
     *
     * <p>If the key is a key list, then a {@link Future} will be returned that aggregates the results of each key in
     * the key list, possibly nested.
     *
     * <p>If the key is a threshold key, then a {@link Future} will be returned that aggregates the results of each key
     * in the threshold key, possibly nested, based on the threshold for that key.
     *
     * @param key The key to check on the verification results for.
     * @return A {@link Future} that will yield the {@link SignatureVerification} for the given key.
     */
    @NonNull
    private Future<SignatureVerification> verificationFutureFor(@NonNull final Key key) {
        return switch (key.key().kind()) {
            case ED25519, ECDSA_SECP256K1 -> {
                final var result = keyVerifications.get(key);
                yield result == null ? completedFuture(failedVerification(key)) : result;
            }
            case KEY_LIST -> {
                final var keys = key.keyListOrThrow().keysOrElse(emptyList());
                yield verificationFutureFor(key, keys, 0);
            }
            case THRESHOLD_KEY -> {
                final var thresholdKey = key.thresholdKeyOrThrow();
                final var keyList = thresholdKey.keysOrElse(KeyList.DEFAULT);
                final var keys = keyList.keysOrElse(emptyList());
                final var threshold = thresholdKey.threshold();
                final var clampedThreshold = Math.min(Math.max(1, threshold), keys.size());
                yield verificationFutureFor(key, keys, keys.size() - clampedThreshold);
            }
            case CONTRACT_ID, DELEGATABLE_CONTRACT_ID, ECDSA_384, RSA_3072, UNSET -> completedFuture(
                    failedVerification(key));
        };
    }

    /**
     * Utility method that converts the keys into a list of {@link Future<SignatureVerification>} and then aggregates
     * them into a single {@link Future<SignatureVerification>}.
     *
     * @param key The key that is being verified.
     * @param keys The sub-keys of the key being verified
     * @param numCanFail The number of sub-keys that can fail verification before the key itself does
     * @return A {@link Future<SignatureVerification>}
     */
    @NonNull
    private Future<SignatureVerification> verificationFutureFor(
            @NonNull final Key key, @NonNull final List<Key> keys, final int numCanFail) {
        // If there are no keys, then we always fail. There must be at least one key in a key list or threshold key
        // for it to be a valid key and to pass any form of verification.
        if (keys.isEmpty() || numCanFail < 0) return completedFuture(failedVerification(key));
        final var futures = keys.stream().map(this::verificationFutureFor).toList();
        return new CompoundSignatureVerificationFuture(key, null, futures, numCanFail);
    }

    @NonNull
    private SignatureVerification resolveFuture(
            @NonNull final Future<SignatureVerification> future,
            @NonNull final Supplier<SignatureVerification> fallback) {
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for signature verification", e);
        } catch (final TimeoutException e) {
            logger.warn("Timed out while waiting for signature verification, probably going to ISS soon", e);
        } catch (final ExecutionException e) {
            logger.error("An unexpected exception was thrown while waiting for SignatureVerification", e);
        }
        return fallback.get();
    }
}
