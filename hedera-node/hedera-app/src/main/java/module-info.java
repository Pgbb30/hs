module com.hedera.node.app {
    requires transitive com.hedera.node.app.hapi.utils;
    requires transitive com.hedera.node.app.service.consensus.impl;
    requires transitive com.hedera.node.app.service.consensus;
    requires transitive com.hedera.node.app.service.contract.impl;
    requires transitive com.hedera.node.app.service.file.impl;
    requires transitive com.hedera.node.app.service.mono;
    requires transitive com.hedera.node.app.service.network.admin.impl;
    requires transitive com.hedera.node.app.service.schedule.impl;
    requires transitive com.hedera.node.app.service.token.impl;
    requires transitive com.hedera.node.app.service.util.impl;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.config;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config;
    requires transitive com.swirlds.jasperdb;
    requires transitive com.swirlds.merkle;
    requires transitive com.swirlds.virtualmap;
    requires transitive dagger;
    requires transitive grpc.netty;
    requires transitive javax.inject;
    requires com.hedera.node.app.hapi.fees;
    requires com.hedera.node.app.service.contract;
    requires com.hedera.node.app.service.evm;
    requires com.hedera.node.app.service.file;
    requires com.hedera.node.app.service.network.admin;
    requires com.hedera.node.app.service.schedule;
    requires com.hedera.node.app.service.token;
    requires com.hedera.node.app.service.util;
    requires com.google.common;
    requires com.google.protobuf;
    requires com.swirlds.fchashmap;
    requires com.swirlds.fcqueue;
    requires com.swirlds.platform;
    requires grpc.stub;
    requires io.grpc;
    requires io.netty.handler;
    requires io.netty.transport.classes.epoll;
    requires org.apache.commons.lang3;
    requires org.apache.logging.log4j;
    requires org.hyperledger.besu.datatypes;
    requires org.hyperledger.besu.evm;
    requires org.slf4j;
    requires static com.github.spotbugs.annotations;

    exports com.hedera.node.app to
            com.swirlds.platform;
    exports com.hedera.node.app.state to
            com.swirlds.common,
            com.hedera.node.app.test.fixtures;
    exports com.hedera.node.app.workflows to
            com.hedera.node.app.test.fixtures;
    exports com.hedera.node.app.state.merkle to
            com.swirlds.common;
    exports com.hedera.node.app.state.merkle.disk to
            com.swirlds.common;
    exports com.hedera.node.app.state.merkle.memory to
            com.swirlds.common;
    exports com.hedera.node.app.state.merkle.singleton to
            com.swirlds.common;
    exports com.hedera.node.app.authorization to
            com.swirlds.platform;
    exports com.hedera.node.app.state.merkle.adapters to
            com.swirlds.platform;
    exports com.hedera.node.app.fees to
            com.swirlds.platform;
    exports com.hedera.node.app.throttle to
            com.swirlds.platform;
    exports com.hedera.node.app.workflows.dispatcher;
    exports com.hedera.node.app.config;
    exports com.hedera.node.app.workflows.handle.validation;
    exports com.hedera.node.app.state.recordcache to
            com.swirlds.common;
    exports com.hedera.node.app.services to
            com.swirlds.platform;
    exports com.hedera.node.app.signature to
            com.hedera.node.app.test.fixtures;
    exports com.hedera.node.app.info to
            com.hedera.node.app.test.fixtures,
            com.swirlds.common,
            com.swirlds.platform;
}
