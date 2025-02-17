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

package com.hedera.node.app.service.file.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.preValidate;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.validateAndAddRequiredKeys;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.verifyNotSystemFile;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.service.file.impl.ReadableFileStoreImpl;
import com.hedera.node.app.service.file.impl.WritableFileStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.LedgerConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#SYSTEM_DELETE}.
 */
@Singleton
public class FileSystemDeleteHandler implements TransactionHandler {
    @Inject
    public FileSystemDeleteHandler() {
        // Exists for injection
    }

    /**
     * This method is called during the pre-handle workflow.
     *
     * <p>Determines signatures needed for deleting a system file
     *
     * @param context the {@link PreHandleContext} which collects all information that will be
     *     passed to {@code handle()}
     * @throws NullPointerException if any of the arguments are {@code null}
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);

        final var transactionBody = context.body().systemDeleteOrThrow();
        final var fileStore = context.createStore(ReadableFileStoreImpl.class);
        final var fileMeta = preValidate(transactionBody.fileID(), fileStore, context, true);

        validateAndAddRequiredKeys(fileMeta.keys(), context, true);
    }

    @Override
    public void handle(@NonNull final HandleContext handleContext) throws HandleException {
        requireNonNull(handleContext);

        final var systemDeleteTransactionBody = handleContext.body().systemDeleteOrThrow();
        if (!systemDeleteTransactionBody.hasFileID()) {
            throw new HandleException(INVALID_FILE_ID);
        }
        var fileId = systemDeleteTransactionBody.fileIDOrThrow();

        final var ledgerConfig = handleContext.configuration().getConfigData(LedgerConfig.class);

        final var fileStore = handleContext.writableStore(WritableFileStore.class);
        final File file = verifyNotSystemFile(ledgerConfig, fileStore, fileId);

        final var newExpiry = systemDeleteTransactionBody
                .expirationTimeOrElse(new TimestampSeconds(file.expirationTime()))
                .seconds();
        // If the file is already expired, remove it from state completely otherwise change the deleted flag to be true.
        if (newExpiry <= handleContext.consensusNow().getEpochSecond()) {
            fileStore.removeFile(fileId);
        } else {
            /* Get all the fields from existing file and change deleted flag */
            final var fileBuilder = new File.Builder()
                    .fileId(file.fileId())
                    .expirationTime(newExpiry)
                    .keys(file.keys())
                    .contents(file.contents())
                    .memo(file.memo())
                    .deleted(true);

            /* --- Put the modified file. It will be in underlying state's modifications map.
            It will not be committed to state until commit is called on the state.--- */
            fileStore.put(fileBuilder.build());
        }
    }
}
