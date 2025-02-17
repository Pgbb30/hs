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

package com.hedera.node.app.grpc.impl.netty;

import static io.netty.handler.ssl.SupportedCipherSuiteFilter.INSTANCE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.node.app.grpc.GrpcServerManager;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.node.config.data.NettyConfig;
import com.swirlds.common.metrics.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An implementation of {@link GrpcServerManager} based on Helidon gRPC.
 *
 * <p>This implementation uses two different ports for gRPC and gRPC+TLS. If the TLS server cannot be started, then
 * a warning is logged, but we continue to function without TLS. This is useful during testing and local development
 * where TLS may not be available.
 */
@Singleton
public final class NettyGrpcServerManager implements GrpcServerManager {
    /** The logger instance for this class. */
    private static final Logger logger = LogManager.getLogger(NettyGrpcServerManager.class);
    /** The supported ciphers for TLS */
    private static final List<String> SUPPORTED_CIPHERS = List.of(
            "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_AES_256_GCM_SHA384");
    /** The supported protocols for TLS */
    private static final List<String> SUPPORTED_PROTOCOLS = List.of("TLSv1.2", "TLSv1.3");

    /** The set of {@link ServiceDescriptor}s for services that the gRPC server will expose */
    private final Set<ServerServiceDefinition> services;
    /** The configuration provider, so we can figure out ports and other information. */
    private final ConfigProvider configProvider;
    /** The gRPC server listening on the plain (non-tls) port */
    private Server plainServer;
    /** The gRPC server listening on the plain TLS port */
    private Server tlsServer;

    /**
     * Create a new instance.
     *
     * @param configProvider The config provider, so we can figure out ports and other information.
     * @param servicesRegistry The set of all services registered with the system
     * @param ingestWorkflow The implementation of the {@link IngestWorkflow} to use for transaction rpc methods
     * @param queryWorkflow The implementation of the {@link QueryWorkflow} to use for query rpc methods
     * @param metrics Used to get/create metrics for each transaction and query method.
     */
    @Inject
    public NettyGrpcServerManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final ServicesRegistry servicesRegistry,
            @NonNull final IngestWorkflow ingestWorkflow,
            @NonNull final QueryWorkflow queryWorkflow,
            @NonNull final Metrics metrics) {
        this.configProvider = requireNonNull(configProvider);
        requireNonNull(ingestWorkflow);
        requireNonNull(queryWorkflow);
        requireNonNull(metrics);

        // Convert the various RPC service definitions into transaction or query endpoints using the GrpcServiceBuilder.
        services = servicesRegistry.services().stream()
                .flatMap(s -> s.rpcDefinitions().stream())
                .map(d -> {
                    final var builder = new GrpcServiceBuilder(d.basePath(), ingestWorkflow, queryWorkflow);
                    d.methods().forEach(m -> {
                        if (Transaction.class.equals(m.requestType())) {
                            builder.transaction(m.path());
                        } else {
                            builder.query(m.path());
                        }
                    });
                    return builder.build(metrics);
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public int port() {
        return plainServer == null ? -1 : plainServer.getPort();
    }

    @Override
    public int tlsPort() {
        return tlsServer == null ? -1 : tlsServer.getPort();
    }

    @Override
    public boolean isRunning() {
        return plainServer != null && !plainServer.isShutdown();
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            logger.error("Cannot start gRPC servers, they have already been started!");
            throw new IllegalStateException("Server already started");
        }

        logger.info("Starting gRPC servers");
        final var nettyConfig = configProvider.getConfiguration().getConfigData(NettyConfig.class);
        final var startRetries = nettyConfig.startRetries();
        final var startRetryIntervalMs = nettyConfig.startRetryIntervalMs();
        final var grpcConfig = configProvider.getConfiguration().getConfigData(GrpcConfig.class);
        final var port = grpcConfig.port();

        // Start the plain-port server
        logger.debug("Starting gRPC server on port {}", port);
        var nettyBuilder = builderFor(port, nettyConfig);
        plainServer = startServerWithRetry(nettyBuilder, startRetries, startRetryIntervalMs);
        logger.debug("gRPC server listening on port {}", plainServer.getPort());

        // Try to start the server listening on the tls port. If this doesn't start, then we just keep going. We should
        // rethink whether we want to have two ports per consensus node like this. We do expose both via the proxies,
        // but we could have either TLS or non-TLS only on the node itself and have the proxy manage making a TLS
        // connection or terminating it, as appropriate. But for now, we support both, with the TLS port being optional.
        try {
            final var tlsPort = grpcConfig.tlsPort();
            logger.debug("Starting TLS gRPC server on port {}", tlsPort);
            nettyBuilder = builderFor(tlsPort, nettyConfig);
            configureTls(nettyBuilder, nettyConfig);
            tlsServer = startServerWithRetry(nettyBuilder, startRetries, startRetryIntervalMs);
            logger.debug("TLS gRPC server listening on port {}", tlsServer.getPort());
        } catch (SSLException | FileNotFoundException e) {
            tlsServer = null;
            logger.warn("Could not start TLS server, will continue without it: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void stop() {
        logger.info("Shutting down gRPC servers");
        if (plainServer != null) {
            logger.info("Shutting down gRPC server on port {}", plainServer.getPort());
            terminateServer(plainServer);
            plainServer = null;
        }

        if (tlsServer != null) {
            logger.info("Shutting down TLS gRPC server on port {}", tlsServer.getPort());
            terminateServer(tlsServer);
            tlsServer = null;
        }
    }

    /**
     * Attempts to start the server. It will retry {@code startRetries} times until it finally gives up with
     * {@code startRetryIntervalMs} between attempts.
     *
     * @param nettyBuilder The builder used to create the server to start
     * @param startRetries The number of times to retry, if needed. Non-negative (enforced by config).
     * @param startRetryIntervalMs The time interval between retries. Positive (enforced by config).
     */
    Server startServerWithRetry(
            @NonNull final NettyServerBuilder nettyBuilder, final int startRetries, final long startRetryIntervalMs) {

        requireNonNull(nettyBuilder);

        // Setup the GRPC Routing, such that all grpc services are registered
        services.forEach(nettyBuilder::addService);
        final var server = nettyBuilder.build();

        var remaining = startRetries;
        while (remaining > 0) {
            try {
                server.start();
                return server;
            } catch (IOException e) {
                remaining--;
                if (remaining == 0) {
                    throw new RuntimeException("Failed to start gRPC server");
                }
                logger.info("Still trying to start server... {} tries remaining", remaining);

                // Wait a bit before retrying. In the FUTURE we should consider removing this functionality, it isn't
                // clear that it is actually helpful, and it complicates the code. But for now we will keep it so as
                // to remain as compatible as we can with previous non-modular releases.
                try {
                    Thread.sleep(startRetryIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting to retry server start", ie);
                }
            }
        }

        throw new RuntimeException("Failed to start gRPC server");
    }

    /**
     * Terminates the given server
     *
     * @param server the server to terminate
     */
    private void terminateServer(@Nullable final Server server) {
        if (server == null) {
            return;
        }

        final var nettyConfig = configProvider.getConfiguration().getConfigData(NettyConfig.class);
        final var terminationTimeout = nettyConfig.terminationTimeout();

        try {
            server.awaitTermination(terminationTimeout, TimeUnit.SECONDS);
            logger.info("gRPC server stopped");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for gRPC to terminate!", ie);
        } catch (Exception e) {
            logger.warn("Exception while waiting for gRPC to terminate!", e);
        }
    }

    /** Utility for setting up various shared configuration settings between both servers */
    private NettyServerBuilder builderFor(final int port, NettyConfig config) {
        NettyServerBuilder builder;
        try {
            builder = NettyServerBuilder.forPort(port)
                    .keepAliveTime(config.prodKeepAliveTime(), TimeUnit.SECONDS)
                    .permitKeepAliveTime(config.prodKeepAliveTime(), TimeUnit.SECONDS)
                    .keepAliveTimeout(config.prodKeepAliveTimeout(), TimeUnit.SECONDS)
                    .maxConnectionAge(config.prodMaxConnectionAge(), TimeUnit.SECONDS)
                    .maxConnectionAgeGrace(config.prodMaxConnectionAgeGrace(), TimeUnit.SECONDS)
                    .maxConnectionIdle(config.prodMaxConnectionIdle(), TimeUnit.SECONDS)
                    .maxConcurrentCallsPerConnection(config.prodMaxConcurrentCalls())
                    .flowControlWindow(config.prodFlowControlWindow())
                    .directExecutor()
                    .channelType(EpollServerSocketChannel.class)
                    .bossEventLoopGroup(new EpollEventLoopGroup())
                    .workerEventLoopGroup(new EpollEventLoopGroup());
            logger.info("Using Epoll for gRPC server");
        } catch (final Throwable ignored) {
            // If we can't use Epoll, then just use NIO
            logger.info("Epoll not available, using NIO");
            builder = NettyServerBuilder.forPort(port)
                    .keepAliveTime(config.prodKeepAliveTime(), TimeUnit.SECONDS)
                    .permitKeepAliveTime(config.prodKeepAliveTime(), TimeUnit.SECONDS)
                    .keepAliveTimeout(config.prodKeepAliveTimeout(), TimeUnit.SECONDS)
                    .maxConnectionAge(config.prodMaxConnectionAge(), TimeUnit.SECONDS)
                    .maxConnectionAgeGrace(config.prodMaxConnectionAgeGrace(), TimeUnit.SECONDS)
                    .maxConnectionIdle(config.prodMaxConnectionIdle(), TimeUnit.SECONDS)
                    .maxConcurrentCallsPerConnection(config.prodMaxConcurrentCalls())
                    .flowControlWindow(config.prodFlowControlWindow())
                    .directExecutor();
        }

        return builder;
    }

    /** Utility for setting up TLS configuration */
    private void configureTls(final NettyServerBuilder builder, NettyConfig config)
            throws SSLException, FileNotFoundException {
        final var tlsCrtPath = config.tlsCrtPath();
        final var crt = new File(tlsCrtPath);
        if (!crt.exists()) {
            logger.warn("Specified TLS cert '{}' doesn't exist!", tlsCrtPath);
            throw new FileNotFoundException(tlsCrtPath);
        }

        final var tlsKeyPath = config.tlsKeyPath();
        final var key = new File(tlsKeyPath);
        if (!key.exists()) {
            logger.warn("Specified TLS key '{}' doesn't exist!", tlsKeyPath);
            throw new FileNotFoundException(tlsKeyPath);
        }

        final var sslContext = GrpcSslContexts.configure(SslContextBuilder.forServer(crt, key))
                .protocols(SUPPORTED_PROTOCOLS)
                .ciphers(SUPPORTED_CIPHERS, INSTANCE)
                .build();

        builder.sslContext(sslContext);
    }
}
