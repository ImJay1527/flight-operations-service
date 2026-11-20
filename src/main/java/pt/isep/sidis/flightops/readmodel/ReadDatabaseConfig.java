package pt.isep.sidis.flightops.readmodel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Short timeouts for the read database: with it down, a query must fall back to the write side within a couple of
 * seconds instead of waiting for the driver's default (30 s to find a server). Applied on top of the URI's options.
 */
@Configuration
@ConditionalOnProperty(name = "flightops.read-model.enabled", havingValue = "true")
public class ReadDatabaseConfig {

    @Bean
    MongoClientSettingsBuilderCustomizer readDatabaseTimeouts(
            @Value("${flightops.read-db.timeout-ms:2000}") int timeoutMs,
            @Value("${flightops.read-db.read-timeout-ms:5000}") int readTimeoutMs) {
        return settings -> settings
                .applyToClusterSettings(c -> c.serverSelectionTimeout(timeoutMs, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(s -> s.connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS));
    }
}
