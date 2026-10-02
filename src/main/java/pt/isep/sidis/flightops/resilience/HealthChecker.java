package pt.isep.sidis.flightops.resilience;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pt.isep.sidis.flightops.clients.HttpClientFactory;

import java.time.Duration;

/**
 * Active health checks (PL3 p.12): GET /actuator/health on every known instance every few seconds. A failure counts
 * towards opening its circuit; a success closes it, so a recovered instance is used again without waiting for
 * user traffic (PL3 p.14).
 */
@Component
public class HealthChecker {

    private final HealthRegistry registry;
    private final RestClient restClient;

    public HealthChecker(HealthRegistry registry, HttpClientFactory httpClientFactory, RestClient.Builder builder,
                         @Value("${sidis.resilience.health-check-timeout-ms:1000}") long timeoutMs) {
        this.registry = registry;
        this.restClient = builder.clone().requestFactory(httpClientFactory.create(Duration.ofMillis(timeoutMs))).build();
    }

    @Scheduled(initialDelayString = "${sidis.resilience.health-check-interval-ms:5000}",
               fixedDelayString = "${sidis.resilience.health-check-interval-ms:5000}")
    public void checkAll() {
        registry.all().forEach(this::check);
    }

    void check(EndpointHealth endpoint) {
        try {
            restClient.get().uri(endpoint.url() + "/actuator/health").retrieve().toBodilessEntity();
            endpoint.recordSuccess();
        } catch (Exception e) {
            endpoint.recordFailure("health check failed: " + e.getMessage());
        }
    }
}
