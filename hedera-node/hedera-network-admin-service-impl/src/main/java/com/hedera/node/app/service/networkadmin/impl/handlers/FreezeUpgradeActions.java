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

package com.hedera.node.app.service.networkadmin.impl.handlers;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.runAsync;

import com.hedera.node.app.spi.state.WritableFreezeStore;
import com.hedera.node.config.data.NetworkAdminServiceConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FreezeUpgradeActions {
    private static final Logger log = LogManager.getLogger(FreezeUpgradeActions.class);

    private static final String PREPARE_UPGRADE_DESC = "software";
    private static final String TELEMETRY_UPGRADE_DESC = "telemetry";
    private static final String MANUAL_REMEDIATION_ALERT = "Manual remediation may be necessary to avoid node ISS";

    public static final String NOW_FROZEN_MARKER = "now_frozen.mf";
    public static final String EXEC_IMMEDIATE_MARKER = "execute_immediate.mf";
    public static final String EXEC_TELEMETRY_MARKER = "execute_telemetry.mf";
    public static final String FREEZE_SCHEDULED_MARKER = "freeze_scheduled.mf";
    public static final String FREEZE_ABORTED_MARKER = "freeze_aborted.mf";

    public static final String MARK = "✓";

    private final NetworkAdminServiceConfig adminServiceConfig;
    private final WritableFreezeStore freezeStore;

    @Inject
    public FreezeUpgradeActions(
            @NonNull final NetworkAdminServiceConfig adminServiceConfig,
            @NonNull final WritableFreezeStore freezeStore) {
        requireNonNull(adminServiceConfig);
        requireNonNull(freezeStore);

        this.adminServiceConfig = adminServiceConfig;
        this.freezeStore = freezeStore;
    }

    public void externalizeFreezeIfUpgradePending() {
        // @todo('Issue #6201'): call networkCtx.hasPreparedUpgrade()
        // final var isUpgradePrepared = networkCtx.hasPreparedUpgrade();
        final boolean isUpgradePrepared = true;

        if (isUpgradePrepared) {
            writeCheckMarker(NOW_FROZEN_MARKER);
        }
    }

    public CompletableFuture<Void> extractTelemetryUpgrade(
            @NonNull final byte[] archiveData, @Nullable final Instant now) {
        requireNonNull(archiveData);
        return extractNow(archiveData, TELEMETRY_UPGRADE_DESC, EXEC_TELEMETRY_MARKER, now);
    }

    public CompletableFuture<Void> extractSoftwareUpgrade(@NonNull final byte[] archiveData) {
        requireNonNull(archiveData);
        return extractNow(archiveData, PREPARE_UPGRADE_DESC, EXEC_IMMEDIATE_MARKER, null);
    }

    public CompletableFuture<Void> scheduleFreezeOnlyAt(@NonNull final Instant freezeTime) {
        requireNonNull(freezeTime);
        withNonNullDualState("schedule freeze", ds -> ds.freezeTime(freezeTime));
        return CompletableFuture.completedFuture(null); // return a future which completes immediately
    }

    public CompletableFuture<Void> scheduleFreezeUpgradeAt(@NonNull final Instant freezeTime) {
        requireNonNull(freezeTime);
        withNonNullDualState("schedule freeze", ds -> {
            ds.freezeTime(freezeTime);
            writeSecondMarker(FREEZE_SCHEDULED_MARKER, freezeTime);
        });
        return CompletableFuture.completedFuture(null); // return a future which completes immediately
    }

    public CompletableFuture<Void> abortScheduledFreeze() {
        withNonNullDualState("abort freeze", ds -> {
            ds.freezeTime(null);
            writeCheckMarker(FREEZE_ABORTED_MARKER);
        });
        return CompletableFuture.completedFuture(null); // return a future which completes immediately
    }

    public boolean isFreezeScheduled() {
        final var ans = new AtomicBoolean();
        withNonNullDualState("check freeze schedule", ds -> {
            final var freezeTime = ds.freezeTime();
            ans.set(freezeTime != null && !freezeTime.equals(ds.lastFrozenTime()));
        });
        return ans.get();
    }

    /* --- Internal methods --- */

    private CompletableFuture<Void> extractNow(
            @NonNull final byte[] archiveData,
            @NonNull final String desc,
            @NonNull final String marker,
            @Nullable final Instant now) {
        requireNonNull(archiveData);
        requireNonNull(desc);
        requireNonNull(marker);

        final int size = archiveData.length;
        final String artifactsLoc = adminServiceConfig.upgradeArtifactsPath();
        requireNonNull(artifactsLoc);
        log.info("About to unzip {} bytes for {} update into {}", size, desc, artifactsLoc);
        // we spin off a separate thread to avoid blocking handleTransaction
        // if we block handle, there could be a dramatic spike in E2E latency at the time of PREPARE_UPGRADE
        return runAsync(() -> extractAndReplaceArtifacts(artifactsLoc, archiveData, size, desc, marker, now));
    }

    private void extractAndReplaceArtifacts(
            String artifactsLoc, byte[] archiveData, int size, String desc, String marker, Instant now) {
        try {
            try (Stream<Path> paths = Files.walk(Paths.get(artifactsLoc))) {
                // delete any existing files in the artifacts directory
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        } catch (final IOException e) {
            // above is a best-effort delete
            // if it fails, we log the error and continue
            log.error("Failed to delete existing files in {}", artifactsLoc, e);
        }
        try {
            UnzipUtility.unzip(archiveData, Paths.get(artifactsLoc));
            log.info("Finished unzipping {} bytes for {} update into {}", size, desc, artifactsLoc);
            writeSecondMarker(marker, now);
        } catch (final IOException e) {
            // catch and log instead of throwing because upgrade process looks at the presence or absence
            // of marker files to determine whether to proceed with the upgrade
            // if second marker is present, that means the zip file was successfully extracted
            log.error("Failed to unzip archive for NMT consumption", e);
            log.error(MANUAL_REMEDIATION_ALERT);
        }
    }

    private void withNonNullDualState(
            @NonNull final String actionDesc, @NonNull final Consumer<WritableFreezeStore> action) {
        requireNonNull(actionDesc);
        requireNonNull(action);
        requireNonNull(freezeStore, "Cannot " + actionDesc + " without access to the dual state");
        action.accept(freezeStore);
    }

    private void writeCheckMarker(@NonNull final String file) {
        requireNonNull(file);
        writeMarker(file, null);
    }

    private void writeSecondMarker(@NonNull final String file, @Nullable final Instant now) {
        requireNonNull(file);
        writeMarker(file, now);
    }

    private void writeMarker(@NonNull final String file, @Nullable final Instant now) {
        requireNonNull(file);
        final Path artifactsDirPath = Paths.get(adminServiceConfig.upgradeArtifactsPath());
        final var filePath = artifactsDirPath.resolve(file);
        try {
            if (!artifactsDirPath.toFile().exists()) {
                Files.createDirectories(artifactsDirPath);
            }
            final var contents = (now == null) ? MARK : (String.valueOf(now.getEpochSecond()));
            Files.writeString(filePath, contents);
            log.info("Wrote marker {}", filePath);
        } catch (final IOException e) {
            log.error("Failed to write NMT marker {}", filePath, e);
            log.error(MANUAL_REMEDIATION_ALERT);
        }
    }
}
