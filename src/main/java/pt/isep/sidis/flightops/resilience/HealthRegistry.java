package pt.isep.sidis.flightops.resilience;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.monitoring.FlightOpsMetrics;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every remote instance this instance talks to, with its health / circuit breaker state. */
@Component
public class HealthRegistry {

    private final Map<String, EndpointHealth> endpoints = new LinkedHashMap<>();
    private final int failureThreshold;
    private final Duration openDuration;
    private final Duration maxOpenDuration;
    private final Clock clock;
    private final FlightOpsMetrics metrics;

    public HealthRegistry(@Value("${sidis.resilience.circuit.failure-threshold:3}") int failureThreshold,
                          @Value("${sidis.resilience.circuit.open-duration-ms:10000}") long openDurationMs,
                          @Value("${sidis.resilience.circuit.max-open-duration-ms:60000}") long maxOpenDurationMs,
                          FlightOpsMetrics metrics) {
        this.failureThreshold = failureThreshold;
        this.openDuration = Duration.ofMillis(openDurationMs);
        this.maxOpenDuration = Duration.ofMillis(maxOpenDurationMs);
        this.clock = Clock.systemUTC();
        this.metrics = metrics;
    }

    /** Returns the (shared) health record of an instance, creating it the first time. */
    public synchronized EndpointHealth register(String group, String url) {
        return endpoints.computeIfAbsent(url, u -> {
            EndpointHealth health = new EndpointHealth(group, u, failureThreshold, openDuration, maxOpenDuration, clock);
            metrics.registerHealthGauge(health);   // PL3 p.19: peer availability
            return health;
        });
    }

    public synchronized List<EndpointHealth> all() {
        return new ArrayList<>(endpoints.values());
    }

    public List<EndpointHealth.Snapshot> snapshot() {
        return all().stream().map(EndpointHealth::snapshot).toList();
    }
}
