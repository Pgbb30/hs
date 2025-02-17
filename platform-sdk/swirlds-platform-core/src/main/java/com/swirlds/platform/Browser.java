/*
 * Copyright (C) 2016-2023 Hedera Hashgraph, LLC
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

package com.swirlds.platform;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.LogMarker.EXCEPTION;
import static com.swirlds.logging.LogMarker.STARTUP;
import static com.swirlds.platform.crypto.CryptoSetup.initNodeSecurity;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.getBrowserWindow;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.getPlatforms;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.getStateHierarchy;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.setInsets;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.setStateHierarchy;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.showBrowserWindow;
import static com.swirlds.platform.state.GenesisStateBuilder.buildGenesisState;
import static com.swirlds.platform.state.address.AddressBookNetworkUtils.getLocalAddressCount;
import static com.swirlds.platform.state.signed.ReservedSignedState.createNullReservation;
import static com.swirlds.platform.state.signed.SignedStateFileReader.getSavedStateFiles;
import static com.swirlds.platform.system.SystemExitCode.NODE_ADDRESS_MISMATCH;
import static com.swirlds.platform.system.SystemExitUtils.exitSystem;
import static com.swirlds.platform.util.BootstrapUtils.detectSoftwareUpgrade;

import com.swirlds.common.StartupTime;
import com.swirlds.common.config.BasicConfig;
import com.swirlds.common.config.ConsensusConfig;
import com.swirlds.common.config.EventConfig;
import com.swirlds.common.config.OSHealthCheckConfig;
import com.swirlds.common.config.PathsConfig;
import com.swirlds.common.config.SocketConfig;
import com.swirlds.common.config.StateConfig;
import com.swirlds.common.config.TransactionConfig;
import com.swirlds.common.config.WiringConfig;
import com.swirlds.common.config.export.ConfigExport;
import com.swirlds.common.config.singleton.ConfigurationHolder;
import com.swirlds.common.config.sources.LegacyFileConfigSource;
import com.swirlds.common.config.sources.ThreadCountPropertyConfigSource;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.context.DefaultPlatformContext;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.CryptographyHolder;
import com.swirlds.common.crypto.config.CryptoConfig;
import com.swirlds.common.internal.ApplicationDefinition;
import com.swirlds.common.io.config.RecycleBinConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.metrics.Metrics;
import com.swirlds.common.metrics.MetricsProvider;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultMetricsProvider;
import com.swirlds.common.metrics.platform.prometheus.PrometheusConfig;
import com.swirlds.common.system.NodeId;
import com.swirlds.common.system.Platform;
import com.swirlds.common.system.SoftwareVersion;
import com.swirlds.common.system.SwirldMain;
import com.swirlds.common.system.address.Address;
import com.swirlds.common.system.address.AddressBook;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.utility.CommonUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.fchashmap.config.FCHashMapConfig;
import com.swirlds.jasperdb.config.JasperDbConfig;
import com.swirlds.logging.payloads.NodeAddressMismatchPayload;
import com.swirlds.logging.payloads.NodeStartPayload;
import com.swirlds.logging.payloads.SavedStateLoadedPayload;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.config.AddressBookConfig;
import com.swirlds.platform.config.ThreadConfig;
import com.swirlds.platform.config.internal.ConfigMappings;
import com.swirlds.platform.config.internal.PlatformConfigUtils;
import com.swirlds.platform.config.legacy.ConfigPropertiesSource;
import com.swirlds.platform.config.legacy.LegacyConfigProperties;
import com.swirlds.platform.config.legacy.LegacyConfigPropertiesLoader;
import com.swirlds.platform.crypto.CryptoConstants;
import com.swirlds.platform.dispatch.DispatchConfiguration;
import com.swirlds.platform.event.preconsensus.PreconsensusEventStreamConfig;
import com.swirlds.platform.event.tipset.EventCreationConfig;
import com.swirlds.platform.gossip.chatter.config.ChatterConfig;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.gui.GuiPlatformAccessor;
import com.swirlds.platform.gui.internal.InfoApp;
import com.swirlds.platform.gui.internal.InfoMember;
import com.swirlds.platform.gui.internal.InfoSwirld;
import com.swirlds.platform.gui.internal.StateHierarchy;
import com.swirlds.platform.health.OSHealthChecker;
import com.swirlds.platform.health.clock.OSClockSpeedSourceChecker;
import com.swirlds.platform.health.entropy.OSEntropyChecker;
import com.swirlds.platform.health.filesystem.OSFileSystemChecker;
import com.swirlds.platform.network.Network;
import com.swirlds.platform.portforwarding.PortForwarder;
import com.swirlds.platform.portforwarding.PortMapping;
import com.swirlds.platform.reconnect.emergency.EmergencySignedStateValidator;
import com.swirlds.platform.recovery.EmergencyRecoveryManager;
import com.swirlds.platform.state.State;
import com.swirlds.platform.state.address.AddressBookInitializer;
import com.swirlds.platform.state.address.AddressBookNetworkUtils;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SavedStateInfo;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateFileUtils;
import com.swirlds.platform.swirldapp.AppLoaderException;
import com.swirlds.platform.swirldapp.SwirldAppLoader;
import com.swirlds.platform.system.Shutdown;
import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.uptime.UptimeConfig;
import com.swirlds.platform.util.MetricsDocUtils;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.JFrame;
import javax.swing.UIManager;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.core.util.DefaultShutdownCallbackRegistry;
import org.apache.logging.log4j.spi.LoggerContextFactory;

/**
 * The Browser that launches the Platforms that run the apps.
 */
public class Browser {
    // Each member is represented by an AddressBook entry in config.txt. On a given computer, a single java
    // process runs all members whose listed internal IP address matches some address on that computer. That
    // Java process will instantiate one Platform per member running on that machine. But there will be only
    // one static Browser that they all share.
    //
    // Every member, whatever computer it is running on, listens on 0.0.0.0, on its internal port. Every
    // member connects to every other member, by computing its IP address as follows: If that other member
    // is also on the same host, use 127.0.0.1. If it is on the same LAN[*], use its internal address.
    // Otherwise, use its external address.
    //
    // This way, a single config.txt can be shared across computers unchanged, even if, for example, those
    // computers are on different networks in Amazon EC2.
    //
    // [*] Two members are considered to be on the same LAN if their listed external addresses are the same.

    private static Logger logger = LogManager.getLogger(Browser.class);

    private static Thread[] appRunThreads;

    private static Browser INSTANCE;

    private final Configuration configuration;

    // @formatter:off
    private static final String STARTUP_MESSAGE =
            """
              //////////////////////
             // Node is Starting //
            //////////////////////""";
    // @formatter:on

    final Shutdown shutdown = new Shutdown();

    /**
     * Prevent this class from being instantiated.
     */
    private Browser(@NonNull final Set<NodeId> localNodesToStart) throws IOException {
        Objects.requireNonNull(localNodesToStart, "localNodesToStart must not be null");
        logger.info(STARTUP.getMarker(), "\n\n" + STARTUP_MESSAGE + "\n");
        logger.debug(STARTUP.getMarker(), () -> new NodeStartPayload().toString());

        // The properties from the config.txt
        final LegacyConfigProperties configurationProperties = LegacyConfigPropertiesLoader.loadConfigFile(
                ConfigurationHolder.getConfigData(PathsConfig.class).getConfigPath());

        final ConfigSource settingsConfigSource = LegacyFileConfigSource.ofSettingsFile();
        final ConfigSource mappedSettingsConfigSource = ConfigMappings.addConfigMapping(settingsConfigSource);

        final ConfigSource configPropertiesConfigSource = new ConfigPropertiesSource(configurationProperties);
        final ConfigSource threadCountPropertyConfigSource = new ThreadCountPropertyConfigSource();

        // Load config.txt file, parse application jar file name, main class name, address book, and parameters
        final ApplicationDefinition appDefinition =
                ApplicationDefinitionLoader.load(configurationProperties, localNodesToStart);

        ParameterProvider.getInstance().setParameters(appDefinition.getAppParameters());

        // Load all SwirldMain instances for locally run nodes.
        final Map<NodeId, SwirldMain> appMains = loadSwirldMains(appDefinition, localNodesToStart);

        // Load Configuration Definitions
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSource(mappedSettingsConfigSource)
                .withSource(configPropertiesConfigSource)
                .withSource(threadCountPropertyConfigSource)
                .withConfigDataType(BasicConfig.class)
                .withConfigDataType(StateConfig.class)
                .withConfigDataType(CryptoConfig.class)
                .withConfigDataType(TemporaryFileConfig.class)
                .withConfigDataType(ReconnectConfig.class)
                .withConfigDataType(FCHashMapConfig.class)
                .withConfigDataType(JasperDbConfig.class)
                .withConfigDataType(MerkleDbConfig.class)
                .withConfigDataType(ChatterConfig.class)
                .withConfigDataType(AddressBookConfig.class)
                .withConfigDataType(VirtualMapConfig.class)
                .withConfigDataType(ConsensusConfig.class)
                .withConfigDataType(ThreadConfig.class)
                .withConfigDataType(DispatchConfiguration.class)
                .withConfigDataType(MetricsConfig.class)
                .withConfigDataType(PrometheusConfig.class)
                .withConfigDataType(OSHealthCheckConfig.class)
                .withConfigDataType(WiringConfig.class)
                .withConfigDataType(PreconsensusEventStreamConfig.class)
                .withConfigDataType(SyncConfig.class)
                .withConfigDataType(UptimeConfig.class)
                .withConfigDataType(RecycleBinConfig.class)
                .withConfigDataType(EventConfig.class)
                .withConfigDataType(EventCreationConfig.class)
                .withConfigDataType(PathsConfig.class)
                .withConfigDataType(SocketConfig.class)
                .withConfigDataType(TransactionConfig.class);

        // Assume all locally run instances provide the same configuration definitions to the configuration builder.
        if (appMains.size() > 0) {
            appMains.values().iterator().next().updateConfigurationBuilder(configurationBuilder);
        }

        this.configuration = configurationBuilder.build();
        PlatformConfigUtils.checkConfiguration(configuration);

        // Set the configuration on all SwirldMain instances.
        appMains.values().forEach(swirldMain -> swirldMain.setConfiguration(configuration));

        ConfigurationHolder.getInstance().setConfiguration(configuration);
        CryptographyHolder.reset();

        OSHealthChecker.performOSHealthChecks(
                configuration.getConfigData(OSHealthCheckConfig.class),
                List.of(
                        OSClockSpeedSourceChecker::performClockSourceSpeedCheck,
                        OSEntropyChecker::performEntropyChecks,
                        OSFileSystemChecker::performFileSystemCheck));

        try {
            // discover the inset size and set the look and feel
            if (!GraphicsEnvironment.isHeadless()) {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                final JFrame jframe = new JFrame();
                jframe.setPreferredSize(new Dimension(200, 200));
                jframe.pack();
                setInsets(jframe.getInsets());
                jframe.dispose();
            }

            // Write the settingsUsed.txt file
            writeSettingsUsed(configuration);

            // find all the apps in data/apps and stored states in data/states
            setStateHierarchy(new StateHierarchy(null));

            // read from config.txt (in same directory as .jar, usually sdk/)
            // to fill in the following three variables, which define the
            // simulation to run.

            try {
                final PathsConfig pathsConfig = configuration.getConfigData(PathsConfig.class);
                if (Files.exists(pathsConfig.getConfigPath())) {
                    CommonUtils.tellUserConsole(
                            "Reading the configuration from the file:   " + pathsConfig.getConfigPath());
                } else {
                    CommonUtils.tellUserConsole(
                            "A config.txt file could be created here:   " + pathsConfig.getConfigPath());
                    return;
                }
                // instantiate all Platform objects, which each instantiates a Statistics object
                logger.debug(STARTUP.getMarker(), "About to run startPlatforms()");
                startPlatforms(configuration, appDefinition, appMains);

                // create the browser window, which uses those Statistics objects
                showBrowserWindow();
                for (final Frame f : Frame.getFrames()) {
                    if (!f.equals(getBrowserWindow())) {
                        f.toFront();
                    }
                }

                CommonUtils.tellUserConsole(
                        "This computer has an internal IP address:  " + Network.getInternalIPAddress());
                logger.trace(
                        STARTUP.getMarker(),
                        "All of this computer's addresses: {}",
                        () -> (Arrays.toString(Network.getOwnAddresses2())));

                // port forwarding
                final SocketConfig socketConfig = configuration.getConfigData(SocketConfig.class);
                if (socketConfig.doUpnp()) {
                    final List<PortMapping> portsToBeMapped = new LinkedList<>();
                    synchronized (getPlatforms()) {
                        for (final Platform p : getPlatforms()) {
                            final Address address = p.getSelfAddress();
                            final String ip = Address.ipString(address.getListenAddressIpv4());
                            final PortMapping pm = new PortMapping(
                                    ip,
                                    // ip address (not used by portMapper, which tries all external port
                                    // network
                                    // interfaces)
                                    // (should probably use ports >50000, this is considered the dynamic
                                    // range)
                                    address.getPortInternal(),
                                    address.getPortExternal(), // internal port
                                    PortForwarder.Protocol.TCP // transport protocol
                                    );
                            portsToBeMapped.add(pm);
                        }
                    }
                    Network.doPortForwarding(getStaticThreadManager(), portsToBeMapped);
                }
            } catch (final Exception e) {
                logger.error(EXCEPTION.getMarker(), "", e);
            }

        } catch (final Exception e) {
            logger.error(EXCEPTION.getMarker(), "", e);
        }

        logger.debug(STARTUP.getMarker(), "main() finished");
    }

    /**
     * Load all {@link SwirldMain} instances for locally run nodes.  Locally run nodes are indicated in two possible
     * ways.  One is through the set of local nodes to start.  The other is through {@link Address::isOwnHost} being
     * true.
     *
     * @param appDefinition     the application definition
     * @param localNodesToStart the locally run nodeIds
     * @return a map from nodeIds to {@link SwirldMain} instances
     * @throws AppLoaderException             if there are issues loading the user app
     * @throws ConstructableRegistryException if there are issues registering
     *                                        {@link com.swirlds.common.constructable.RuntimeConstructable} classes
     */
    @NonNull
    private Map<NodeId, SwirldMain> loadSwirldMains(
            @NonNull final ApplicationDefinition appDefinition, @NonNull final Set<NodeId> localNodesToStart) {
        Objects.requireNonNull(appDefinition, "appDefinition must not be null");
        Objects.requireNonNull(localNodesToStart, "localNodesToStart must not be null");
        try {
            // Create the SwirldAppLoader
            final SwirldAppLoader appLoader;
            try {
                appLoader =
                        SwirldAppLoader.loadSwirldApp(appDefinition.getMainClassName(), appDefinition.getAppJarPath());
            } catch (final AppLoaderException e) {
                CommonUtils.tellUserConsolePopup("ERROR", e.getMessage());
                throw e;
            }

            // Register all RuntimeConstructable classes
            logger.debug(STARTUP.getMarker(), "Scanning the classpath for RuntimeConstructable classes");
            final long start = System.currentTimeMillis();
            ConstructableRegistry.getInstance().registerConstructables("", appLoader.getClassLoader());
            logger.debug(
                    STARTUP.getMarker(),
                    "Done with registerConstructables, time taken {}ms",
                    System.currentTimeMillis() - start);

            // Create the SwirldMain instances
            final Map<NodeId, SwirldMain> appMains = new HashMap<>();
            final AddressBook addressBook = appDefinition.getAddressBook();
            for (final Address address : addressBook) {
                if (AddressBookNetworkUtils.isLocal(address)) {
                    // if the local nodes to start are not specified, start all local nodes. Otherwise, start specified.
                    if (localNodesToStart.isEmpty() || localNodesToStart.contains(address.getNodeId())) {
                        appMains.put(address.getNodeId(), buildAppMain(appDefinition, appLoader));
                    }
                }
            }
            return appMains;
        } catch (final Exception ex) {
            throw new RuntimeException("Error loading SwirldMains", ex);
        }
    }

    /**
     * Writes all settings and config values to settingsUsed.txt
     *
     * @param configuration the configuration values to write
     */
    private void writeSettingsUsed(final Configuration configuration) {
        final StringBuilder settingsUsedBuilder = new StringBuilder();

        // Add all settings values to the string builder
        final PathsConfig pathsConfig = configuration.getConfigData(PathsConfig.class);
        if (Files.exists(pathsConfig.getSettingsPath())) {
            PlatformConfigUtils.generateSettingsUsed(settingsUsedBuilder, configuration);
        }

        settingsUsedBuilder.append(System.lineSeparator());
        settingsUsedBuilder.append("-------------Configuration Values-------------");
        settingsUsedBuilder.append(System.lineSeparator());

        // Add all config values to the string builder
        ConfigExport.addConfigContents(configuration, settingsUsedBuilder);

        // Write the settingsUsed.txt file
        final Path settingsUsedPath =
                pathsConfig.getSettingsUsedDir().resolve(PlatformConfigUtils.SETTING_USED_FILENAME);
        try (final OutputStream outputStream = new FileOutputStream(settingsUsedPath.toFile())) {
            outputStream.write(settingsUsedBuilder.toString().getBytes(StandardCharsets.UTF_8));
        } catch (final IOException | RuntimeException e) {
            logger.error(STARTUP.getMarker(), "Failed to write settingsUsed to file {}", settingsUsedPath, e);
        }
    }

    /**
     * Start the browser running, if it isn't already running. If it's already running, then Browser.main does nothing.
     * Normally, an app calling Browser.main has no effect, because it was the browser that launched the app in the
     * first place, so the browser is already running.
     * <p>
     * But during app development, it can be convenient to give the app a main method that calls Browser.main. If there
     * is a config.txt file that says to run the app that is being developed, then the developer can run the app within
     * Eclipse. Eclipse will call the app's main() method, which will call the browser's main() method, which launches
     * the browser. The app's main() then returns, and the app stops running. Then the browser will load the app
     * (because of the config.txt file) and let it run normally within the browser. All of this happens within Eclipse,
     * so the Eclipse debugger works, and Eclipse breakpoints within the app will work.
     *
     * @param args args is ignored, and has no effect
     */
    public static synchronized void parseCommandLineArgsAndLaunch(final String... args) {
        if (INSTANCE != null) {
            return;
        }

        // This set contains the nodes set by the command line to start, if none are passed, then IP
        // addresses will be compared to determine which node to start
        final Set<NodeId> localNodesToStart = new HashSet<>();

        // Parse command line arguments (rudimentary parsing)
        String currentOption = null;
        if (args != null) {
            for (final String item : args) {
                final String arg = item.trim().toLowerCase();
                if (arg.equals("-local")) {
                    currentOption = arg;
                } else if (currentOption != null) {
                    try {
                        localNodesToStart.add(new NodeId(Integer.parseInt(arg)));
                    } catch (final NumberFormatException ex) {
                        // Intentionally suppress the NumberFormatException
                    }
                }
            }
        }

        launch(
                localNodesToStart,
                ConfigurationHolder.getConfigData(PathsConfig.class).getLogPath());
    }

    /**
     * Launch the browser.
     *
     * @param localNodesToStart a set of nodes that should be started in this JVM instance
     * @param log4jPath         the path to the log4j configuraiton file, if null then log4j is not started
     */
    public static synchronized void launch(final Set<NodeId> localNodesToStart, final Path log4jPath) {
        if (INSTANCE != null) {
            return;
        }

        // Initialize the log4j2 configuration and logging subsystem if a log4j2.xml file is present in the current
        // working directory
        try {
            if (log4jPath != null) {
                Log4jSetup.startLoggingFramework(log4jPath);
            }
            logger = LogManager.getLogger(Browser.class);

            if (Thread.getDefaultUncaughtExceptionHandler() == null) {
                Thread.setDefaultUncaughtExceptionHandler((final Thread t, final Throwable e) ->
                        logger.error(EXCEPTION.getMarker(), "exception on thread {}", t.getName(), e));
            }

            final LoggerContextFactory factory = LogManager.getFactory();
            if (factory instanceof final Log4jContextFactory contextFactory) {
                // Do not allow log4j to use its own shutdown hook. Use our own shutdown
                // hook to stop log4j. This allows us to write a final log message before
                // the logger is shut down.
                ((DefaultShutdownCallbackRegistry) contextFactory.getShutdownCallbackRegistry()).stop();
                Runtime.getRuntime()
                        .addShutdownHook(new ThreadConfiguration(getStaticThreadManager())
                                .setComponent("browser")
                                .setThreadName("shutdown-hook")
                                .setRunnable(() -> {
                                    logger.info(STARTUP.getMarker(), "JVM is shutting down.");
                                    LogManager.shutdown();
                                })
                                .build());
            }
        } catch (final Exception e) {
            LogManager.getLogger(Browser.class).fatal("Unable to load log context", e);
            System.err.println("FATAL Unable to load log context: " + e);
        }
        try {
            StartupTime.markStartupTime();
            INSTANCE = new Browser(localNodesToStart);
        } catch (IOException e) {
            throw new RuntimeException("Unable to create Browser", e);
        }
    }

    /**
     * Instantiate and start the thread dump generator.
     */
    private void startThreadDumpGenerator() {
        final ThreadConfig threadConfig = configuration.getConfigData(ThreadConfig.class);

        if (threadConfig.threadDumpPeriodMs() > 0) {
            final Path dir = getAbsolutePath(threadConfig.threadDumpLogDir());
            if (!Files.exists(dir)) {
                rethrowIO(() -> Files.createDirectories(dir));
            }
            logger.info(STARTUP.getMarker(), "Starting thread dump generator and save to directory {}", dir);
            ThreadDumpGenerator.generateThreadDumpAtIntervals(dir, threadConfig.threadDumpPeriodMs());
        }
    }

    /**
     * Instantiate and start the JVMPauseDetectorThread, if enabled via the
     * {@link BasicConfig#jvmPauseDetectorSleepMs()} setting.
     */
    private void startJVMPauseDetectorThread(@NonNull final Configuration configuration) {
        final BasicConfig basicConfig = Objects.requireNonNull(configuration).getConfigData(BasicConfig.class);
        if (basicConfig.jvmPauseDetectorSleepMs() > 0) {
            final JVMPauseDetectorThread jvmPauseDetectorThread = new JVMPauseDetectorThread(
                    (pauseTimeMs, allocTimeMs) -> {
                        if (pauseTimeMs > basicConfig.jvmPauseReportMs()) {
                            logger.warn(
                                    EXCEPTION.getMarker(),
                                    "jvmPauseDetectorThread detected JVM paused for {} ms, allocation pause {} ms",
                                    pauseTimeMs,
                                    allocTimeMs);
                        }
                    },
                    basicConfig.jvmPauseDetectorSleepMs());
            jvmPauseDetectorThread.start();
            logger.debug(STARTUP.getMarker(), "jvmPauseDetectorThread started");
        }
    }

    /**
     * Build the app main.
     *
     * @param appDefinition the app definition
     * @param appLoader     an object capable of loading the app
     * @return the new app main
     */
    private static SwirldMain buildAppMain(final ApplicationDefinition appDefinition, final SwirldAppLoader appLoader) {
        try {
            return appLoader.instantiateSwirldMain();
        } catch (final Exception e) {
            CommonUtils.tellUserConsolePopup(
                    "ERROR",
                    "ERROR: There are problems starting class " + appDefinition.getMainClassName() + "\n"
                            + ExceptionUtils.getStackTrace(e));
            logger.error(EXCEPTION.getMarker(), "Problems with class {}", appDefinition.getMainClassName(), e);
            throw new RuntimeException(e);
        }
    }

    private Collection<SwirldsPlatform> createLocalPlatforms(
            @NonNull final ApplicationDefinition appDefinition,
            @NonNull final Map<NodeId, Crypto> crypto,
            @NonNull final InfoSwirld infoSwirld,
            @NonNull final Map<NodeId, SwirldMain> appMains,
            @NonNull final Configuration configuration,
            @NonNull final MetricsProvider metricsProvider) {
        Objects.requireNonNull(appDefinition, "the app definition must not be null");
        Objects.requireNonNull(crypto, "the crypto array must not be null");
        Objects.requireNonNull(infoSwirld, "the infoSwirld must not be null");
        Objects.requireNonNull(appMains, "the appMains map must not be null");
        Objects.requireNonNull(configuration, "the configuration must not be null");
        Objects.requireNonNull(metricsProvider, "the metricsProvider must not be null");

        final List<SwirldsPlatform> platforms = new ArrayList<>();

        final AddressBook addressBook = appDefinition.getAddressBook();

        int ownHostIndex = 0;

        for (int nodeIndex = 0; nodeIndex < addressBook.getSize(); nodeIndex++) {
            final NodeId nodeId = addressBook.getNodeId(nodeIndex);
            final Address address = addressBook.getAddress(nodeId);
            final int instanceNumber = addressBook.getIndexOfNodeId(nodeId);

            if (appMains.containsKey(nodeId)) {
                // this is a node to start locally.
                final String platformName = address.getNickname()
                        + " - " + address.getSelfName()
                        + " - " + infoSwirld.name
                        + " - " + infoSwirld.app.name;

                final PlatformContext platformContext =
                        new DefaultPlatformContext(nodeId, metricsProvider, configuration);

                SwirldMain appMain = appMains.get(nodeId);

                // name of the app's SwirldMain class
                final String mainClassName = appDefinition.getMainClassName();
                // the name of this swirld
                final String swirldName = appDefinition.getSwirldName();
                final SoftwareVersion appVersion = appMain.getSoftwareVersion();

                final RecycleBin recycleBin;
                try {
                    recycleBin = RecycleBin.create(configuration, nodeId);
                } catch (IOException e) {
                    throw new UncheckedIOException("unable to create recycle bin", e);
                }

                // We can't send a "real" dispatch, since the dispatcher will not have been started by the
                // time this class is used.
                final BasicConfig basicConfig =
                        platformContext.getConfiguration().getConfigData(BasicConfig.class);
                final EmergencyRecoveryManager emergencyRecoveryManager =
                        new EmergencyRecoveryManager(shutdown::shutdown, basicConfig.getEmergencyRecoveryFileLoadDir());

                final ReservedSignedState initialState = getInitialState(
                        platformContext,
                        appMain,
                        mainClassName,
                        swirldName,
                        nodeId,
                        addressBook,
                        emergencyRecoveryManager);

                final SwirldsPlatform platform;
                try (initialState) {
                    // check software version compatibility
                    final boolean softwareUpgrade = detectSoftwareUpgrade(appVersion, initialState.get());

                    if (softwareUpgrade) {
                        try {
                            logger.info(
                                    STARTUP.getMarker(), "Clearing recycle bin as part of software upgrade workflow.");
                            recycleBin.clear();
                        } catch (final IOException e) {
                            throw new UncheckedIOException("Failed to clear recycle bin", e);
                        }
                    }

                    // Initialize the address book from the configuration and platform saved state.
                    final AddressBookInitializer addressBookInitializer = new AddressBookInitializer(
                            appVersion, softwareUpgrade, initialState.get(), addressBook.copy(), platformContext);

                    if (!initialState.get().isGenesisState()) {
                        updateLoadedStateAddressBook(
                                initialState.get(), addressBookInitializer.getInitialAddressBook());
                    }

                    GuiPlatformAccessor.getInstance().setPlatformName(nodeId, platformName);
                    GuiPlatformAccessor.getInstance().setSwirldId(nodeId, appDefinition.getSwirldId());
                    GuiPlatformAccessor.getInstance().setInstanceNumber(nodeId, instanceNumber);

                    platform = new SwirldsPlatform(
                            platformContext,
                            crypto.get(nodeId),
                            recycleBin,
                            nodeId,
                            mainClassName,
                            swirldName,
                            appVersion,
                            initialState.get(),
                            emergencyRecoveryManager);
                }

                platforms.add(platform);

                new InfoMember(infoSwirld, instanceNumber, platform);

                appMain.init(platform, nodeId);

                final Thread appThread = new ThreadConfiguration(getStaticThreadManager())
                        .setNodeId(nodeId)
                        .setComponent("app")
                        .setThreadName("appMain")
                        .setRunnable(appMain)
                        .build();
                // IMPORTANT: this swirlds app thread must be non-daemon,
                // so that the JVM will not exit when the main thread exits
                appThread.setDaemon(false);
                appRunThreads[ownHostIndex] = appThread;

                ownHostIndex++;
                synchronized (getPlatforms()) {
                    getPlatforms().add(platform);
                }
            }
        }

        return Collections.unmodifiableList(platforms);
    }

    /**
     * Create a copy of the initial signed state. There are currently data structures that become immutable after
     * being hashed, and we need to make a copy to force it to become mutable again.
     *
     * @param platformContext    the platform's context
     * @param initialSignedState the initial signed state
     * @return a copy of the initial signed state
     */
    private static ReservedSignedState copyInitialSignedState(
            @NonNull final PlatformContext platformContext, @NonNull final SignedState initialSignedState) {

        final State stateCopy = initialSignedState.getState().copy();
        final SignedState signedStateCopy =
                new SignedState(platformContext, stateCopy, "Browser create new copy of initial state");
        signedStateCopy.setSigSet(initialSignedState.getSigSet());

        return signedStateCopy.reserve("Browser copied initial state");
    }

    /**
     * Update the address book with the current address book read from config.txt. Eventually we will not do this, and
     * only transactions will be capable of modifying the address book.
     *
     * @param signedState the state that was loaded from disk
     * @param addressBook the address book specified in config.txt
     */
    private static void updateLoadedStateAddressBook(
            @NonNull final SignedState signedState, @NonNull final AddressBook addressBook) {

        final State state = signedState.getState();

        // Update the address book with the current address book read from config.txt.
        // Eventually we will not do this, and only transactions will be capable of
        // modifying the address book.
        state.getPlatformState().setAddressBook(addressBook.copy());
    }

    /**
     * Get the initial state to be used by this node. May return a state loaded from disk, or may return a genesis state
     * if no valid state is found on disk.
     *
     * @param platformContext          the platform context
     * @param appMain                  the app main
     * @param mainClassName            the name of the app's SwirldMain class
     * @param swirldName               the name of this swirld
     * @param selfId                   the node id of this node
     * @param configAddressBook        the address book from config.txt
     * @param emergencyRecoveryManager the emergency recovery manager
     * @return the initial state to be used by this node
     */
    @NonNull
    private ReservedSignedState getInitialState(
            @NonNull final PlatformContext platformContext,
            @NonNull final SwirldMain appMain,
            @NonNull final String mainClassName,
            @NonNull final String swirldName,
            @NonNull final NodeId selfId,
            @NonNull final AddressBook configAddressBook,
            @NonNull final EmergencyRecoveryManager emergencyRecoveryManager) {

        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(mainClassName);
        Objects.requireNonNull(swirldName);
        Objects.requireNonNull(selfId);
        Objects.requireNonNull(configAddressBook);
        Objects.requireNonNull(emergencyRecoveryManager);

        final ReservedSignedState loadedState = getUnmodifiedSignedStateFromDisk(
                platformContext,
                mainClassName,
                swirldName,
                selfId,
                appMain.getSoftwareVersion(),
                configAddressBook,
                emergencyRecoveryManager);

        try (loadedState) {
            if (loadedState.isNotNull()) {
                logger.info(
                        STARTUP.getMarker(),
                        new SavedStateLoadedPayload(
                                loadedState.get().getRound(), loadedState.get().getConsensusTimestamp()));

                return copyInitialSignedState(platformContext, loadedState.get());
            }
        }

        final ReservedSignedState genesisState =
                buildGenesisState(platformContext, configAddressBook, appMain.getSoftwareVersion(), appMain.newState());

        try (genesisState) {
            return copyInitialSignedState(platformContext, genesisState.get());
        }
    }

    /**
     * Load the signed state from the disk if it is present.
     *
     * @param mainClassName            the name of the app's SwirldMain class.
     * @param swirldName               the name of the swirld to load the state for.
     * @param selfId                   the ID of the node to load the state for.
     * @param appVersion               the version of the app to use for emergency recovery.
     * @param configAddressBook        the address book to use for emergency recovery.
     * @param emergencyRecoveryManager the emergency recovery manager to use for emergency recovery.
     * @return the signed state loaded from disk.
     */
    @NonNull
    private ReservedSignedState getUnmodifiedSignedStateFromDisk(
            @NonNull final PlatformContext platformContext,
            @NonNull final String mainClassName,
            @NonNull final String swirldName,
            @NonNull final NodeId selfId,
            @NonNull final SoftwareVersion appVersion,
            @NonNull final AddressBook configAddressBook,
            @NonNull final EmergencyRecoveryManager emergencyRecoveryManager) {

        final String actualMainClassName =
                configuration.getConfigData(StateConfig.class).getMainClassName(mainClassName);

        final SavedStateInfo[] savedStateFiles = getSavedStateFiles(actualMainClassName, selfId, swirldName);

        // We can't send a "real" dispatcher for shutdown, since the dispatcher will not have been started by the
        // time this class is used.
        final SavedStateLoader savedStateLoader = new SavedStateLoader(
                platformContext,
                shutdown::shutdown,
                configAddressBook,
                savedStateFiles,
                appVersion,
                () -> new EmergencySignedStateValidator(emergencyRecoveryManager.getEmergencyRecoveryFile()),
                emergencyRecoveryManager);
        try {
            return savedStateLoader.getSavedStateToLoad();
        } catch (final Exception e) {
            logger.error(EXCEPTION.getMarker(), "Signed state not loaded from disk:", e);
            if (configuration.getConfigData(StateConfig.class).requireStateLoad()) {
                exitSystem(SystemExitCode.SAVED_STATE_NOT_LOADED);
            }
        }
        return createNullReservation();
    }

    /**
     * Instantiate and run all the local platforms specified in the given config.txt file. This method reads in and
     * parses the config.txt file.
     *
     * @throws UnknownHostException problems getting an IP address for another user
     * @throws SocketException      problems getting the IP address for self
     */
    private void startPlatforms(
            @NonNull final Configuration configuration,
            @NonNull final ApplicationDefinition appDefinition,
            @NonNull final Map<NodeId, SwirldMain> appMains) {

        final AddressBook addressBook = appDefinition.getAddressBook();

        // If enabled, clean out the signed state directory. Needs to be done before the platform/state is started up,
        // as we don't want to delete the temporary file directory if it ends up being put in the saved state directory.
        final StateConfig stateConfig = configuration.getConfigData(StateConfig.class);
        final String mainClassName = stateConfig.getMainClassName(appDefinition.getMainClassName());
        if (stateConfig.cleanSavedStateDirectory()) {
            SignedStateFileUtils.cleanStateDirectory(mainClassName);
        }

        final int ownHostCount = Math.min(getLocalAddressCount(addressBook), appMains.size());
        logger.info(STARTUP.getMarker(), "there are {} nodes with local IP addresses", ownHostCount);

        // if the local machine did not match any address in the address book then we should log an error and exit
        if (ownHostCount < 1) {
            final String externalIpAddress = (Network.getExternalIpAddress() != null)
                    ? Network.getExternalIpAddress().getIpAddress()
                    : null;
            logger.error(
                    EXCEPTION.getMarker(),
                    new NodeAddressMismatchPayload(Network.getInternalIPAddress(), externalIpAddress));
            exitSystem(NODE_ADDRESS_MISMATCH);
        }

        // the thread for each Platform.run
        // will create a new thread with a new Platform for each local address
        // general address number addIndex is local address number i
        appRunThreads = new Thread[ownHostCount];
        appDefinition.setMasterKey(new byte[CryptoConstants.SYM_KEY_SIZE_BYTES]);
        appDefinition.setSwirldId(new byte[CryptoConstants.HASH_SIZE_BYTES]);

        // Create the various keys and certificates (which are saved in various Crypto objects).
        // Save the certificates in the trust stores.
        // Save the trust stores in the address book.

        logger.debug(STARTUP.getMarker(), "About do crypto instantiation");
        final Map<NodeId, Crypto> crypto = initNodeSecurity(appDefinition.getAddressBook(), configuration);
        logger.debug(STARTUP.getMarker(), "Done with crypto instantiation");

        // the AddressBook is not changed after this point, so we calculate the hash now
        CryptographyHolder.get().digestSync(addressBook);

        final InfoApp infoApp = getStateHierarchy().getInfoApp(appDefinition.getApplicationName());
        final InfoSwirld infoSwirld = new InfoSwirld(infoApp, appDefinition.getSwirldId());

        logger.debug(STARTUP.getMarker(), "Starting platforms");

        // Setup metrics system
        final DefaultMetricsProvider metricsProvider = new DefaultMetricsProvider(configuration);
        final Metrics globalMetrics = metricsProvider.createGlobalMetrics();
        CryptoMetrics.registerMetrics(globalMetrics);

        // Create all instances for all nodes that should run locally
        final Collection<SwirldsPlatform> platforms =
                createLocalPlatforms(appDefinition, crypto, infoSwirld, appMains, configuration, metricsProvider);

        // Write all metrics information to file
        MetricsDocUtils.writeMetricsDocumentToFile(globalMetrics, getPlatforms(), configuration);

        platforms.forEach(SwirldsPlatform::start);

        for (int nodeIndex = 0; nodeIndex < appRunThreads.length; nodeIndex++) {
            appRunThreads[nodeIndex].start();
        }

        // Initialize the thread dump generator, if enabled via settings
        startThreadDumpGenerator();

        // Initialize JVMPauseDetectorThread, if enabled via settings
        startJVMPauseDetectorThread(configuration);

        logger.info(STARTUP.getMarker(), "Starting metrics");
        metricsProvider.start();

        logger.debug(STARTUP.getMarker(), "Done with starting platforms");
    }

    public static void main(final String[] args) {
        parseCommandLineArgsAndLaunch(args);
    }
}
