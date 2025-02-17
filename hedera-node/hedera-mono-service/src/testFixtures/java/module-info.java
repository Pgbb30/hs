module com.hedera.node.app.service.mono.test.fixtures {
    exports com.hedera.test.utils;
    exports com.hedera.test.factories.txns;
    exports com.hedera.test.factories.keys;
    exports com.hedera.test.mocks;
    exports com.hedera.test.factories.scenarios;

    requires transitive com.hedera.node.app.hapi.utils;
    requires transitive com.hedera.node.app.service.mono;
    requires transitive com.hedera.node.app.service.token;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.google.protobuf;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.merkle;
    requires transitive com.swirlds.virtualmap;
    requires transitive org.bouncycastle.provider;
    requires com.hedera.node.app.service.evm;
    requires com.google.common;
    requires com.hedera.pbj.runtime;
    requires net.i2p.crypto.eddsa;
    requires org.junit.jupiter.api;
    requires org.mockito;
    requires static com.github.spotbugs.annotations;
}
