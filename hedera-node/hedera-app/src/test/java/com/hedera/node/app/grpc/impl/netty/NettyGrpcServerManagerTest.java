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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.config.VersionedConfigImpl;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.common.metrics.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class NettyGrpcServerManagerTest {

    private ConfigProvider configProvider;
    private ServicesRegistry services;
    private IngestWorkflow ingestWorkflow;
    private QueryWorkflow queryWorkflow;
    private Metrics metrics;

    @BeforeEach
    void setUp(@Mock @NonNull final Metrics metrics) {
        final var config = HederaTestConfigBuilder.createConfig();

        this.configProvider = () -> new VersionedConfigImpl(config, 1);
        this.metrics = metrics;
        this.services = Set::of; // An empty set of services
        this.ingestWorkflow = (req, res) -> {};
        this.queryWorkflow = (req, res) -> {};
    }

    @Test
    @DisplayName("Null arguments are not allowed")
    @SuppressWarnings("DataFlowIssue")
    void nullArgsThrow() {
        assertThatThrownBy(() -> new NettyGrpcServerManager(null, services, ingestWorkflow, queryWorkflow, metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () -> new NettyGrpcServerManager(configProvider, null, ingestWorkflow, queryWorkflow, metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(configProvider, services, null, queryWorkflow, metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(configProvider, services, ingestWorkflow, null, metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () -> new NettyGrpcServerManager(configProvider, services, ingestWorkflow, queryWorkflow, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Ports are -1 when not started")
    void portsAreMinusOneWhenNotStarted() {
        final var subject =
                new NettyGrpcServerManager(configProvider, services, ingestWorkflow, queryWorkflow, metrics);
        assertThat(subject.port()).isEqualTo(-1);
        assertThat(subject.tlsPort()).isEqualTo(-1);
    }
}
