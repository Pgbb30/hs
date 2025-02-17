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

package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.EXEC_IMMEDIATE_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.EXEC_TELEMETRY_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.FREEZE_ABORTED_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.FREEZE_SCHEDULED_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.NOW_FROZEN_MARKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.app.spi.state.WritableFreezeStore;
import com.hedera.node.config.data.NetworkAdminServiceConfig;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class FreezeUpgradeActionsTest {
    private static final Instant then = Instant.ofEpochSecond(1_234_567L, 890);
    private Path noiseFileLoc;
    private Path noiseSubFileLoc;
    private Path zipArchivePath; // path to valid.zip test zip file (in zipSourceDir directory)

    @TempDir
    private Path zipSourceDir; // temp directory to place test zip files

    @TempDir
    private File zipOutputDir; // temp directory to place marker files and output of zip extraction

    @Mock
    private WritableFreezeStore freezeStore;

    @Mock
    private NetworkAdminServiceConfig adminServiceConfig;

    @LoggingTarget
    private LogCaptor logCaptor;

    @LoggingSubject
    private FreezeUpgradeActions subject;

    @BeforeEach
    void setUp() throws IOException {
        noiseFileLoc = zipOutputDir.toPath().resolve("forgotten.cfg");
        noiseSubFileLoc = zipOutputDir.toPath().resolve("edargpu");

        subject = new FreezeUpgradeActions(adminServiceConfig, freezeStore);

        // set up test zip
        zipSourceDir = Files.createTempDirectory("zipSourceDir");
        zipArchivePath = Path.of(zipSourceDir + "/valid.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipArchivePath.toFile()))) {
            ZipEntry e = new ZipEntry("garden_path_sentence.txt");
            out.putNextEntry(e);

            String fileContent = "The old man the boats";
            byte[] data = fileContent.getBytes();
            out.write(data, 0, data.length);
            out.closeEntry();
        }
    }

    @Test
    void complainsLoudlyWhenUnableToUnzipArchive() {
        rmIfPresent(EXEC_IMMEDIATE_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        final byte[] invalidArchive = "Not a valid zip archive".getBytes(StandardCharsets.UTF_8);
        subject.extractSoftwareUpgrade(invalidArchive).join();

        assertThat(logCaptor.errorLogs())
                .anyMatch(l -> l.startsWith("Failed to unzip archive for NMT consumption java.io.IOException:" + " "));
        assertThat(logCaptor.errorLogs())
                .anyMatch(l -> l.equals("Manual remediation may be necessary to avoid node ISS"));

        assertThat(new File(zipOutputDir, EXEC_IMMEDIATE_MARKER)).doesNotExist();
    }

    @Test
    void preparesForUpgrade() throws IOException {
        setupNoiseFiles();
        rmIfPresent(EXEC_IMMEDIATE_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        final byte[] realArchive = Files.readAllBytes(zipArchivePath);
        subject.extractSoftwareUpgrade(realArchive).join();

        assertMarkerCreated(EXEC_IMMEDIATE_MARKER, null);
    }

    @Test
    void upgradesTelemetry() throws IOException {
        rmIfPresent(EXEC_TELEMETRY_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        final byte[] realArchive = Files.readAllBytes(zipArchivePath);
        subject.extractTelemetryUpgrade(realArchive, then).join();

        assertMarkerCreated(EXEC_TELEMETRY_MARKER, then);
    }

    @Test
    void externalizesFreeze() throws IOException {
        rmIfPresent(NOW_FROZEN_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        subject.externalizeFreezeIfUpgradePending();

        assertMarkerCreated(NOW_FROZEN_MARKER, null);
    }

    @Test
    void setsExpectedFreezeAndWritesMarkerForFreezeUpgrade() throws IOException {
        rmIfPresent(FREEZE_SCHEDULED_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        subject.scheduleFreezeUpgradeAt(then);

        verify(freezeStore).freezeTime(then);

        assertMarkerCreated(FREEZE_SCHEDULED_MARKER, then);
    }

    @Test
    void setsExpectedFreezeOnlyForFreezeOnly() {
        rmIfPresent(FREEZE_SCHEDULED_MARKER);

        subject.scheduleFreezeOnlyAt(then);

        verify(freezeStore).freezeTime(then);
    }

    @Test
    void nullsOutDualOnAborting() throws IOException {
        rmIfPresent(FREEZE_ABORTED_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        subject.abortScheduledFreeze();

        verify(freezeStore).freezeTime(null);

        assertMarkerCreated(FREEZE_ABORTED_MARKER, null);
    }

    @Test
    void canStillWriteMarkersEvenIfDirDoesntExist() throws IOException {
        final Path otherMarkerFilesLoc = noiseSubFileLoc;
        rmIfPresent(otherMarkerFilesLoc, FREEZE_ABORTED_MARKER);
        final var d = otherMarkerFilesLoc.toFile();
        if (d.exists()) {
            assertThat(d.delete()).isTrue();
        }

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(otherMarkerFilesLoc.toString());

        subject.abortScheduledFreeze();

        verify(freezeStore).freezeTime(null);

        assertMarkerCreated(FREEZE_ABORTED_MARKER, null, otherMarkerFilesLoc);
    }

    @Test
    void determinesIfFreezeIsScheduled() {
        assertThat(subject.isFreezeScheduled()).isFalse();

        given(freezeStore.freezeTime()).willReturn(then);

        assertThat(subject.isFreezeScheduled()).isTrue();
    }

    private void rmIfPresent(final String file) {
        rmIfPresent(zipOutputDir.toPath(), file);
    }

    private static void rmIfPresent(final Path baseDir, final String file) {
        final File f = baseDir.resolve(file).toFile();
        if (f.exists()) {
            boolean deleted = f.delete();
            assert (deleted);
        }
    }

    private void assertMarkerCreated(final String file, final @Nullable Instant when) throws IOException {
        assertMarkerCreated(file, when, zipOutputDir.toPath());
    }

    private void assertMarkerCreated(final String file, final @Nullable Instant when, final Path baseDir)
            throws IOException {
        final Path filePath = baseDir.resolve(file);
        assertThat(filePath.toFile()).exists();
        final var contents = Files.readString(filePath);
        assertThat(filePath.toFile().delete()).isTrue();

        if (file.equals(EXEC_IMMEDIATE_MARKER)) {
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("About to unzip ")
                            && l.contains(" bytes for software update into " + baseDir)));
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("Finished unzipping ")
                            && l.contains(" bytes for software update into " + baseDir)));
            assertThat(logCaptor.infoLogs()).anyMatch(l -> (l.contains("Wrote marker " + filePath)));
        } else if (file.equals(EXEC_TELEMETRY_MARKER)) {
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("About to unzip ")
                            && l.contains(" bytes for telemetry update into " + baseDir)));
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l ->
                            (l.startsWith("Finished unzipping ") && l.contains(" bytes for telemetry update into ")));
            assertThat(logCaptor.infoLogs()).anyMatch(l -> (l.contains("Wrote marker " + filePath)));
        } else {
            assertThat(logCaptor.infoLogs()).anyMatch(l -> (l.contains("Wrote marker " + filePath)));
        }
        if (when != null) {
            final var writtenEpochSecond = Long.parseLong(contents);
            assertThat(when.getEpochSecond()).isEqualTo(writtenEpochSecond);
        } else {
            assertThat(contents).isEqualTo(FreezeUpgradeActions.MARK);
        }
    }

    private void setupNoiseFiles() throws IOException {
        Files.write(
                noiseFileLoc,
                List.of("There, the eyes are", "Sunlight on a broken column", "There, is a tree swinging"));
        Files.write(
                noiseSubFileLoc,
                List.of(
                        "And voices are",
                        "In the wind's singing",
                        "More distant and more solemn",
                        "Than a fading star"));
    }
}
