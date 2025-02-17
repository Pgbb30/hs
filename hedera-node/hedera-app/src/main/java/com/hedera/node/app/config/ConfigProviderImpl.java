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

package com.hedera.node.app.config;

import static java.util.Objects.requireNonNull;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.converter.AccountIDConverter;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.converter.CongestionMultipliersConverter;
import com.hedera.node.config.converter.ContractIDConverter;
import com.hedera.node.config.converter.EntityScaleFactorsConverter;
import com.hedera.node.config.converter.EntityTypeConverter;
import com.hedera.node.config.converter.FileIDConverter;
import com.hedera.node.config.converter.HederaFunctionalityConverter;
import com.hedera.node.config.converter.KeyValuePairConverter;
import com.hedera.node.config.converter.KnownBlockValuesConverter;
import com.hedera.node.config.converter.LegacyContractIdActivationsConverter;
import com.hedera.node.config.converter.MapAccessTypeConverter;
import com.hedera.node.config.converter.PermissionedAccountsRangeConverter;
import com.hedera.node.config.converter.RecomputeTypeConverter;
import com.hedera.node.config.converter.ScaleFactorConverter;
import com.hedera.node.config.converter.SemanticVersionConverter;
import com.hedera.node.config.converter.SidecarTypeConverter;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ApiPermissionConfig;
import com.hedera.node.config.data.AutoCreationConfig;
import com.hedera.node.config.data.AutoRenew2Config;
import com.hedera.node.config.data.AutoRenewConfig;
import com.hedera.node.config.data.BalancesConfig;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.CacheConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.CryptoCreateWithAliasConfig;
import com.hedera.node.config.data.DevConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.ExpiryConfig;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.IssConfig;
import com.hedera.node.config.data.LazyCreationConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.NettyConfig;
import com.hedera.node.config.data.NetworkAdminServiceConfig;
import com.hedera.node.config.data.QueriesConfig;
import com.hedera.node.config.data.RatesConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.SigsConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.data.StatsConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.data.TopicsConfig;
import com.hedera.node.config.data.TraceabilityConfig;
import com.hedera.node.config.data.UpgradeConfig;
import com.hedera.node.config.data.UtilPrngConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.data.VirtualdatasourceConfig;
import com.hedera.node.config.sources.PropertyConfigSource;
import com.hedera.node.config.validation.EmulatesMapValidator;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.config.ConsensusConfig;
import com.swirlds.common.config.sources.PropertyFileConfigSource;
import com.swirlds.common.config.sources.SystemEnvironmentConfigSource;
import com.swirlds.common.config.sources.SystemPropertiesConfigSource;
import com.swirlds.common.threading.locks.AutoClosableLock;
import com.swirlds.common.threading.locks.Locks;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ObjIntConsumer;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Implementation of the {@link ConfigProvider} interface.
 */
@Singleton
public class ConfigProviderImpl implements ConfigProvider {
    private static final Logger logger = LogManager.getLogger(ConfigProviderImpl.class);

    /**
     * Name of an environment variable that can be used to override the default path to the genesis.properties file (see
     * {@link #GENESIS_PROPERTIES_DEFAULT_PATH}).
     */
    public static final String GENESIS_PROPERTIES_PATH_ENV = "HEDERA_GENESIS_PROPERTIES_PATH";
    /**
     * Name of an environment variable that can be used to override the default path to the application.properties file
     * (see {@link #APPLICATION_PROPERTIES_DEFAULT_PATH}).
     */
    public static final String APPLICATION_PROPERTIES_PATH_ENV = "HEDERA_APP_PROPERTIES_PATH";
    /** Default path to the genesis.properties file. */
    public static final String GENESIS_PROPERTIES_DEFAULT_PATH = "genesis.properties";
    /** Default path to the application.properties file. */
    public static final String APPLICATION_PROPERTIES_DEFAULT_PATH = "application.properties";
    /** Default path to the semantic-version.properties file. */
    private static final String SEMANTIC_VERSION_PROPERTIES_DEFAULT_PATH = "semantic-version.properties";
    /**
     * The actual underlying versioned configuration provided by this provider. This must provided thread-safe access
     * since many threads (ingestion, pre-handle, etc.) will be accessing it from different threads while it is
     * generally only updated on the handle thread (except during startup). The handle thread will also access it.
     */
    private final AtomicReference<VersionedConfiguration> configuration;
    /** Provides synchronous access to updating the configuration to one thread at a time. */
    private final AutoClosableLock updateLock = Locks.createAutoLock();

    /**
     * Create a new instance. You must specify whether to use the genesis.properties file as a source for the
     * configuration. This should only be true if the node is starting from genesis.
     */
    public ConfigProviderImpl(final boolean useGenesisSource) {
        final var builder = createConfigurationBuilder();
        addFileSources(builder, useGenesisSource);
        final Configuration config = builder.build();
        configuration = new AtomicReference<>(new VersionedConfigImpl(config, 0));
    }

    @Override
    @NonNull
    public VersionedConfiguration getConfiguration() {
        return configuration.get();
    }

    /**
     * This method must be called if a property has changed. It will update the configuration and increase the version.
     *
     * @param propertyFileContent the new property file content
     */
    public void update(@NonNull final Bytes propertyFileContent) {
        try (final var ignoredLock = updateLock.lock()) {
            final var builder = createConfigurationBuilder();
            addFileSources(builder, false);
            addByteSource(builder, propertyFileContent);
            final Configuration config = builder.build();
            configuration.set(
                    new VersionedConfigImpl(config, this.configuration.get().getVersion() + 1));
        }
    }

    private ConfigurationBuilder createConfigurationBuilder() {
        return ConfigurationBuilder.create()
                .withSource(SystemEnvironmentConfigSource.getInstance())
                .withSource(SystemPropertiesConfigSource.getInstance())
                .withSource(new PropertyConfigSource(SEMANTIC_VERSION_PROPERTIES_DEFAULT_PATH, 500))
                .withConfigDataType(AccountsConfig.class)
                .withConfigDataType(ApiPermissionConfig.class)
                .withConfigDataType(AutoCreationConfig.class)
                .withConfigDataType(AutoRenew2Config.class)
                .withConfigDataType(AutoRenewConfig.class)
                .withConfigDataType(BalancesConfig.class)
                .withConfigDataType(BlockRecordStreamConfig.class)
                .withConfigDataType(BootstrapConfig.class)
                .withConfigDataType(CacheConfig.class)
                .withConfigDataType(ConsensusConfig.class)
                .withConfigDataType(ContractsConfig.class)
                .withConfigDataType(CryptoCreateWithAliasConfig.class)
                .withConfigDataType(DevConfig.class)
                .withConfigDataType(EntitiesConfig.class)
                .withConfigDataType(ExpiryConfig.class)
                .withConfigDataType(FeesConfig.class)
                .withConfigDataType(FilesConfig.class)
                .withConfigDataType(GrpcConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(IssConfig.class)
                .withConfigDataType(LazyCreationConfig.class)
                .withConfigDataType(LedgerConfig.class)
                .withConfigDataType(NettyConfig.class)
                .withConfigDataType(NetworkAdminServiceConfig.class)
                .withConfigDataType(QueriesConfig.class)
                .withConfigDataType(RatesConfig.class)
                .withConfigDataType(SchedulingConfig.class)
                .withConfigDataType(SigsConfig.class)
                .withConfigDataType(StakingConfig.class)
                .withConfigDataType(StatsConfig.class)
                .withConfigDataType(TokensConfig.class)
                .withConfigDataType(TopicsConfig.class)
                .withConfigDataType(TraceabilityConfig.class)
                .withConfigDataType(UpgradeConfig.class)
                .withConfigDataType(UtilPrngConfig.class)
                .withConfigDataType(VersionConfig.class)
                .withConfigDataType(VirtualdatasourceConfig.class)
                .withConverter(new CongestionMultipliersConverter())
                .withConverter(new EntityScaleFactorsConverter())
                .withConverter(new EntityTypeConverter())
                .withConverter(new KnownBlockValuesConverter())
                .withConverter(new LegacyContractIdActivationsConverter())
                .withConverter(new MapAccessTypeConverter())
                .withConverter(new RecomputeTypeConverter())
                .withConverter(new ScaleFactorConverter())
                .withConverter(new AccountIDConverter())
                .withConverter(new ContractIDConverter())
                .withConverter(new FileIDConverter())
                .withConverter(new HederaFunctionalityConverter())
                .withConverter(new PermissionedAccountsRangeConverter())
                .withConverter(new SidecarTypeConverter())
                .withConverter(new SemanticVersionConverter())
                .withConverter(new KeyValuePairConverter())
                .withConverter(new BytesConverter())
                .withValidator(new EmulatesMapValidator());
    }

    private void addByteSource(@NonNull final ConfigurationBuilder builder, @NonNull final Bytes propertyFileContent) {
        requireNonNull(builder);
        requireNonNull(propertyFileContent);
        try (final var in = propertyFileContent.toInputStream()) {
            final byte[] bytes = in.readAllBytes();
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
                Properties properties = new Properties();
                properties.load(inputStream);
                final PropertyConfigSource configSource = new PropertyConfigSource(properties, 101);
                builder.withSource(configSource);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Can not create config source for bytes", e);
        }
    }

    private void addFileSources(@NonNull final ConfigurationBuilder builder, final boolean useGenesisSource) {
        requireNonNull(builder);

        if (useGenesisSource) {
            try {
                addFileSource(builder, GENESIS_PROPERTIES_PATH_ENV, GENESIS_PROPERTIES_DEFAULT_PATH, 400);
            } catch (final Exception e) {
                throw new IllegalStateException("Can not create config source for genesis properties", e);
            }
        }

        try {
            addFileSource(builder, APPLICATION_PROPERTIES_PATH_ENV, APPLICATION_PROPERTIES_DEFAULT_PATH, 100);
        } catch (final Exception e) {
            throw new IllegalStateException("Can not create config source for application properties", e);
        }
    }

    private void addFileSource(
            @NonNull final ConfigurationBuilder builder,
            @NonNull final String envName,
            @NonNull final String defaultPath,
            final int priority) {
        requireNonNull(builder);
        requireNonNull(envName);
        requireNonNull(defaultPath);

        final ObjIntConsumer<Path> addSource = (path, p) -> {
            if (path.toFile().exists()) {
                if (!path.toFile().isDirectory()) {
                    try {
                        builder.withSource(new PropertyFileConfigSource(path, p));
                    } catch (IOException e) {
                        throw new IllegalStateException("Can not create config source for property file", e);
                    }
                } else {
                    throw new IllegalArgumentException("File " + path + " is a directory and not a property file");
                }
            } else {
                logger.warn("Properties file {} does not exist and won't be used as configuration source", path);
            }
        };

        try {
            final Path propertiesPath =
                    Optional.ofNullable(System.getenv(envName)).map(Path::of).orElseGet(() -> Path.of(defaultPath));
            addSource.accept(propertiesPath, priority);
        } catch (final Exception e) {
            throw new IllegalStateException("Can not create config source for application properties", e);
        }
    }
}
