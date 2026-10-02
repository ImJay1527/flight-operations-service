package pt.isep.sidis.flightops.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.resilience.EndpointHealth;

import java.util.concurrent.TimeUnit;

/**
 * The monitoring metrics of PL3 p.19, recorded with Micrometer (Spring Boot's metrics library):
 * <ul>
 *   <li>{@value #LOOKUPS} (timer, tag {@code source} = local | forwarded | not_found):
 *       response time of a flight lookup, <b>local vs forwarded</b>, and how often forwarding found the flight
 *       (<b>forwarding success rate</b>).</li>
 *   <li>{@value #REMOTE_CALLS} (timer, tags {@code group}, {@code target}, {@code outcome} = success | not_found |
 *       failure | circuit_open): every call to a peer / another service's instance, including retries:
 *       <b>peer failure rates</b>.</li>
 *   <li>{@value #REMOTE_HEALTHY} (gauge 1/0, tags {@code group}, {@code target}): <b>peer availability</b>.</li>
 *   <li>{@code http.server.requests} (recorded by Spring Boot): requests served by this instance, for
 *       <b>load distribution</b>.</li>
 * </ul>
 * Readable summary: GET /api/cluster/metrics. Raw values: GET /actuator/metrics/&lt;name&gt; (ADMIN).
 */
@Component
public class FlightOpsMetrics {

    public static final String LOOKUPS = "flightops.lookups";
    public static final String REMOTE_CALLS = "flightops.remote.calls";
    public static final String REMOTE_HEALTHY = "flightops.remote.healthy";

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

    public void registerHealthGauge(EndpointHealth endpoint) {
        Gauge.builder(REMOTE_HEALTHY, endpoint, e -> e.snapshot().healthy() ? 1 : 0)
                .description("1 if the instance is considered healthy (circuit closed), else 0")
                .tag("group", endpoint.group())
                .tag("target", endpoint.url())
                .register(registry);
    }
}
