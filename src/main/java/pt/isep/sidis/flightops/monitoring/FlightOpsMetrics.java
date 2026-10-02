package pt.isep.sidis.flightops.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.resilience.EndpointHealth;

import java.util.concurrent.TimeUnit;

/**
 * The metrics of PL3 p.19 (Micrometer):
 * <ul>
 *   <li>{@value #LOOKUPS}: lookup times local vs forwarded, and the forwarding success rate;</li>
 *   <li>{@value #REMOTE_CALLS}: every call to another instance, by outcome - peer failure rates;</li>
 *   <li>{@value #REMOTE_HEALTHY}: 1/0 per instance - peer availability;</li>
 *   <li>{@value #REPLICATION}: copies sent to and applied from other instances, and conflicts found;</li>
 *   <li>Spring Boot's {@code http.server.requests}: requests served - load distribution.</li>
 * </ul>
 * Summary: GET /api/cluster/metrics.
 */
@Component
public class FlightOpsMetrics {

    public static final String LOOKUPS = "flightops.lookups";
    public static final String REMOTE_CALLS = "flightops.remote.calls";
    public static final String REMOTE_HEALTHY = "flightops.remote.healthy";
    public static final String REPLICATION = "flightops.replication";

    public static final String LOCAL = "local";
    public static final String FORWARDED = "forwarded";
    public static final String NOT_FOUND = "not_found";

    private final MeterRegistry registry;

    public FlightOpsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public MeterRegistry registry() {
        return registry;
    }

    /** @param source {@link #LOCAL}, {@link #FORWARDED} or {@link #NOT_FOUND} */
    public void recordLookup(String source, long startNanos) {
        Timer.builder(LOOKUPS)
                .description("Flight lookup by number: local vs forwarded to a peer")
                .tag("source", source)
                .publishPercentiles(0.5, 0.95)
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    /** @param outcome success | not_found | failure | circuit_open */
    public void recordRemoteCall(EndpointHealth endpoint, String outcome, long startNanos) {
        Timer.builder(REMOTE_CALLS)
                .description("Calls to peers and to other services' instances (including retries)")
                .tag("group", endpoint.group())
                .tag("target", endpoint.url())
                .tag("outcome", outcome)
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    /** @param event sent | applied | conflict */
    public void recordReplication(String event) {
        Counter.builder(REPLICATION)
                .description("Copies of flights sent to / applied from other instances, and conflicts found")
                .tag("event", event)
                .register(registry)
                .increment();
    }

    public void registerHealthGauge(EndpointHealth endpoint) {
        Gauge.builder(REMOTE_HEALTHY, endpoint, e -> e.snapshot().healthy() ? 1 : 0)
                .description("1 if the instance is considered healthy (circuit closed), else 0")
                .tag("group", endpoint.group())
                .tag("target", endpoint.url())
                .register(registry);
    }
}
