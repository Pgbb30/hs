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

package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * Thrown if the request itself is bad. The protobuf decoded correctly, but it failed one or more of the ingestion
 * pipeline pre-checks.
 */
public class PreCheckException extends Exception {
    private final ResponseCodeEnum responseCode;

    /**
     * Constructor of {@code PreCheckException}
     *
     * @param responseCode the {@link ResponseCodeEnum responseCode}
     * @throws NullPointerException if {@code responseCode} is {@code null}
     */
    public PreCheckException(@NonNull final ResponseCodeEnum responseCode) {
        super();
        this.responseCode = Objects.requireNonNull(responseCode);
    }

    /**
     * <strong>Disallowed</strong> constructor of {@code PreCheckException}.
     * This {@link Exception} subclass is used as a form of unconditional jump, rather than a true
     * exception.  If another {@link Throwable} caused this exception to be thrown, then that other
     * throwable <strong>must</strong> be logged to appropriate diagnostics before the {@code PreCheckException}
     * is thrown.
     *
     * @param responseCode the {@link ResponseCodeEnum responseCode}.  This is ignored.
     * @param cause the {@link Throwable} that caused this exception.  This is ignored.
     * @throws UnsupportedOperationException always.  This constructor must not be called.
     */
    private PreCheckException(@NonNull final ResponseCodeEnum responseCode, @Nullable final Throwable cause) {
        throw new UnsupportedOperationException("PreCheckException must not chain a cause");
    }

    /**
     * {@inheritDoc}
     * This implementation prevents initializing a cause.  PreCheckException is a result code carrier and
     * must not have a cause.  If another {@link Throwable} caused this exception to be thrown, then that other
     * throwable <strong>must</strong> be logged to appropriate diagnostics before the {@code PreCheckException}
     * is thrown.
     * @throws UnsupportedOperationException always.  This method must not be called.
     */
    @Override
    public Throwable initCause(Throwable cause) {
        throw new UnsupportedOperationException("PreCheckException must not chain a cause");
    }

    /**
     * Returns the {@code responseCode} of this {@code PreCheckException}
     *
     * @return the {@link ResponseCodeEnum responseCode}
     */
    @NonNull
    public ResponseCodeEnum responseCode() {
        return responseCode;
    }

    @Override
    public String toString() {
        return "PreCheckException{" + "responseCode=" + responseCode + '}';
    }

    public static void validateTruePreCheck(boolean condition, ResponseCodeEnum errorStatus) throws PreCheckException {
        if (!condition) {
            throw new PreCheckException(errorStatus);
        }
    }

    public static void validateFalsePreCheck(boolean condition, ResponseCodeEnum errorStatus) throws PreCheckException {
        validateTruePreCheck(!condition, errorStatus);
    }
}
