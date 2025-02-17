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

package com.swirlds.platform.cli;

import static com.swirlds.platform.recovery.EventRecoveryWorkflow.recoverState;

import com.swirlds.cli.commands.EventStreamCommand;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.config.ConfigUtils;
import com.swirlds.common.config.singleton.ConfigurationHolder;
import com.swirlds.common.config.sources.LegacyFileConfigSource;
import com.swirlds.common.context.DefaultPlatformContext;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.CryptographyHolder;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.system.NodeId;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import picocli.CommandLine;

@CommandLine.Command(
        name = "recover",
        mixinStandardHelpOptions = true,
        description = "Build a state file by replaying events from an event stream.")
@SubcommandOf(EventStreamCommand.class)
public final class EventStreamRecoverCommand extends AbstractCommand {

    private Path outputPath = Path.of("./out");
    private String appMainName;
    private Path bootstrapSignedState;
    private NodeId selfId;
    private boolean ignorePartialRounds;
    private long finalRound = -1;
    private Path eventStreamDirectory;
    private List<Path> configurationPaths = List.of();
    private boolean loadSigningKeys;

    private EventStreamRecoverCommand() {}

    @CommandLine.Option(
            names = {"-c", "--config"},
            description = "A path to where a configuration file can be found. If not provided then defaults are used.")
    private void setConfigurationPath(final List<Path> configurationPaths) {
        configurationPaths.forEach(this::pathMustExist);
        this.configurationPaths = configurationPaths;
    }

    @CommandLine.Option(
            names = {"-o", "--out"},
            description =
                    "The location where output is written. Default = './out'. " + "Must not exist prior to invocation.")
    private void setOutputPath(final Path outputPath) {
        this.outputPath = outputPath;
    }

    @CommandLine.Parameters(index = "1", description = "The path to a directory tree containing event stream files.")
    private void setEventStreamDirectory(final Path eventStreamDirectory) {
        this.eventStreamDirectory = pathMustExist(eventStreamDirectory.toAbsolutePath());
    }

    @CommandLine.Option(
            names = {"-n", "--main-name"},
            required = true,
            description = "The fully qualified name of the application's main class.")
    private void setAppMainName(final String appMainName) {
        this.appMainName = appMainName;
    }

    @CommandLine.Parameters(
            index = "0",
            description = "The path to the bootstrap SignedState.swh file."
                    + "Events will be replayed on top of this state file.")
    private void setBootstrapSignedState(final Path bootstrapSignedState) {
        this.bootstrapSignedState = pathMustExist(bootstrapSignedState.toAbsolutePath());
    }

    @CommandLine.Option(
            names = {"-i", "--id"},
            required = true,
            description = "The ID of the node that is being used to recover the state. "
                    + "This node's keys should be available locally.")
    private void setSelfId(final long selfId) {
        this.selfId = new NodeId(selfId);
    }

    @CommandLine.Option(
            names = {"-p", "--ignore-partial"},
            description = "if set then any partial rounds at the end of the event stream are ignored. Default = false")
    private void setIgnorePartialRounds(final boolean ignorePartialRounds) {
        this.ignorePartialRounds = ignorePartialRounds;
    }

    @CommandLine.Option(
            names = {"-f", "--final-round"},
            defaultValue = "-1",
            description = "The last round that should be applied to the state, any higher rounds are ignored. "
                    + "Default = apply all available rounds")
    private void setFinalRound(final long finalRound) {
        this.finalRound = finalRound;
    }

    @CommandLine.Option(
            names = {"-s", "--load-signing-keys"},
            defaultValue = "false",
            description = "If present then load the signing keys. If not present, calling platform.sign() will throw.")
    private void setLoadSigningKeys(final boolean loadSigningKeys) {
        this.loadSigningKeys = loadSigningKeys;
    }

    /**
     * Build a configuration object from the provided configuration paths.
     *
     * @return the configuration object
     * @throws IOException if there is an error reading the configuration files
     */
    private Configuration buildConfiguration() throws IOException {
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create();
        ConfigUtils.scanAndRegisterAllConfigTypes(configurationBuilder, Set.of("com.swirlds"));

        for (final Path configurationPath : configurationPaths) {
            System.out.printf("Loading configuration from %s%n", configurationPath);
            configurationBuilder.withSource(new LegacyFileConfigSource(configurationPath));
        }

        final Configuration configuration = configurationBuilder.build();
        ConfigurationHolder.getInstance().setConfiguration(configuration);

        return configuration;
    }

    @Override
    public Integer call() throws Exception {
        final Configuration configuration = buildConfiguration();

        final PlatformContext platformContext =
                new DefaultPlatformContext(configuration, new NoOpMetrics(), CryptographyHolder.get());

        recoverState(
                platformContext,
                bootstrapSignedState,
                configurationPaths,
                eventStreamDirectory,
                appMainName,
                !ignorePartialRounds,
                finalRound,
                outputPath,
                selfId,
                loadSigningKeys);
        return 0;
    }
}
