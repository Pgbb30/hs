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

package com.hedera.node.app.signature.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.service.mono.sigs.utils.MiscCryptoUtils;
import com.hedera.node.app.signature.ExpandedSignaturePair;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.signature.SignatureVerifier;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.SignatureType;
import com.swirlds.common.crypto.TransactionSignature;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A concrete implementation of {@link SignatureVerifier} that uses the {@link Cryptography} engine to verify the
 * signatures.
 */
@Singleton
public final class SignatureVerifierImpl implements SignatureVerifier {

    /** The {@link Cryptography} engine to use for signature verification. */
    private final Cryptography cryptoEngine;

    /** Create a new instance with the given {@link Cryptography} engine. */
    @Inject
    public SignatureVerifierImpl(@NonNull final Cryptography cryptoEngine) {
        this.cryptoEngine = requireNonNull(cryptoEngine);
    }

    @NonNull
    @Override
    public Map<Key, SignatureVerificationFuture> verify(
            @NonNull final Bytes signedBytes, @NonNull final Set<ExpandedSignaturePair> sigs) {
        requireNonNull(signedBytes);
        requireNonNull(sigs);

        Preparer edPreparer = null;
        Preparer ecPreparer = null;

        // Gather each TransactionSignature to send to the platform and the resulting SignatureVerificationFutures
        final var platformSigs = new ArrayList<TransactionSignature>(sigs.size());
        final var futures = new HashMap<Key, SignatureVerificationFuture>(sigs.size());
        for (ExpandedSignaturePair sigPair : sigs) {
            final var kind = sigPair.sigPair().signature().kind();
            final var preparer =
                    switch (kind) {
                        case ECDSA_SECP256K1 -> ecPreparer == null
                                ? ecPreparer = createPreparerForEC(signedBytes)
                                : ecPreparer;
                        case ED25519 -> edPreparer == null ? edPreparer = createPreparerForED(signedBytes) : edPreparer;
                        case CONTRACT, ECDSA_384, RSA_3072, UNSET -> throw new IllegalArgumentException(
                                "Unsupported signature type: " + kind);
                    };

            preparer.addSignature(sigPair.signature());
            preparer.addKey(sigPair.keyBytes());
            final var txSig = preparer.prepareTransactionSignature();
            platformSigs.add(txSig);
            futures.put(sigPair.key(), new SignatureVerificationFutureImpl(sigPair.key(), sigPair.evmAlias(), txSig));
        }

        // Submit to the crypto engine. We do it as a single list of objects to try to cut down on temporary object
        // creation. If you call the platform for a single TransactionSignature at a time, it wraps each in a List.
        cryptoEngine.verifyAsync(platformSigs);
        return futures;
    }

    private static Preparer createPreparerForED(@NonNull final Bytes signedBytes) {
        return new Preparer(signedBytes, SignatureType.ED25519);
    }

    private static Preparer createPreparerForEC(@NonNull final Bytes signedBytes) {
        final var bytes = new byte[(int) signedBytes.length()];
        signedBytes.getBytes(0, bytes, 0, bytes.length);
        return new Preparer(Bytes.wrap(MiscCryptoUtils.keccak256DigestOf(bytes)), SignatureType.ECDSA_SECP256K1);
    }

    // The Hashgraph Platform crypto engine takes a list of TransactionSignature objects to verify. Each of these
    // is fed a byte array of the signed bytes, the public key, the signature, and the signature type, with
    // appropriate offsets. Rather than many small arrays, we're going to create one big array for all ED25519
    // verifications and one for ECDSA_SECP256K1 verifications and reuse them across all TransactionSignature
    // objects.
    private static final class Preparer {
        // Each transaction, encoded as protobuf, is a maximum of 6K, *including* signatures. A single instance of the
        // Preparer is used for verifying keys on a single transaction. If the transaction has 6K signed bytes, there
        // is virtually no space left for signatures. If the transaction has no signed bytes, the maximum number of
        // signatures would still be less than 6K. This array wants to be large enough to not need any copies as it is
        // being built, but small enough to not waste too much space. 10K seems like it will fit the bill. In the off
        // chance that it *is* too small, an array copy will be made to enlarge it.
        private static final int DEFAULT_SIZE = 10 * 1024;
        private final int signedBytesLength;
        private final SignatureType signatureType;
        private byte[] content = new byte[DEFAULT_SIZE];
        private int offset;
        private int signatureOffset;
        private int keyOffset;
        private int signatureLength;
        private int keyLength;

        Preparer(@NonNull final Bytes signedBytes, @NonNull final SignatureType signatureType) {
            this.signatureType = requireNonNull(signatureType);
            signedBytesLength = (int) signedBytes.length();
            signedBytes.getBytes(0, content, 0, signedBytesLength);
            offset = signedBytesLength;
        }

        void addSignature(@NonNull final Bytes signature) {
            signatureOffset = offset;
            signatureLength = (int) signature.length();
            add(signature);
        }

        void addKey(@NonNull final Bytes key) {
            keyOffset = offset;
            keyLength = (int) key.length();
            add(key);
        }

        @NonNull
        TransactionSignature prepareTransactionSignature() {
            return new TransactionSignature(
                    content,
                    signatureOffset,
                    signatureLength,
                    keyOffset,
                    keyLength,
                    0,
                    signedBytesLength,
                    signatureType);
        }

        private void add(@NonNull final Bytes bytes) {
            final var length = (int) bytes.length();
            if (offset + length > content.length) {
                final var oldContent = content;
                content = new byte[oldContent.length * 2];
                System.arraycopy(oldContent, 0, content, 0, offset);
            }
            bytes.getBytes(0, content, offset, length);
            offset += length;
        }
    }
}
