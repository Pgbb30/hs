import com.swirlds.config.api.spi.ConfigurationBuilderFactory;
import com.swirlds.config.impl.internal.ConfigurationBuilderFactoryImpl;

module com.swirlds.config.impl {
    exports com.swirlds.config.impl.converters;
    exports com.swirlds.config.impl.validators;

    requires com.swirlds.base;
    requires com.swirlds.config;
    requires com.swirlds.common;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;

    provides ConfigurationBuilderFactory with
            ConfigurationBuilderFactoryImpl;
}
