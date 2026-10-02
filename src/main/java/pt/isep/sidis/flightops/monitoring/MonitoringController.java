package pt.isep.sidis.flightops.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.sidis.flightops.resilience.EndpointHealth;
import pt.isep.sidis.flightops.resilience.HealthRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** PL3 p.19 monitoring metrics of this instance since it started, in one readable answer. */
@RestController
@RequestMapping("/api/cluster")
@Tag(name = "Cluster")
public class MonitoringController {

    private final MeterRegistry registry;
    private final HealthRegistry healthRegistry;
    private final String instanceName;

    public MonitoringController(FlightOpsMetrics metrics, HealthRegistry healthRegistry,
                                @Value("${flightops.instance-name}") String instanceName) {
        this.registry = metrics.registry();
        this.healthRegistry = healthRegistry;
        this.instanceName = instanceName;
    }

    @Operation(summary = "Monitoring metrics of this instance (PL3 p.19)",
               description = "Response times local vs forwarded, forwarding success rate, peer health and failure rates, "
                           + "requests served. Counted since this instance started.")
    @PreAuthorize("hasAnyRole('ADMIN', 'ATCC', 'BACKOFFICE_OPERATOR')")
    @GetMapping("/metrics")
    public Metrics metrics() {
        TimerStats local = timerStats(FlightOpsMetrics.LOCAL);
        TimerStats forwarded = timerStats(FlightOpsMetrics.FORWARDED);
        TimerStats notFound = timerStats(FlightOpsMetrics.NOT_FOUND);

        long askedPeers = forwarded.count() + notFound.count();
        Double successRate = askedPeers == 0 ? null : round(100.0 * forwarded.count() / askedPeers);

        return new Metrics(instanceName, requestsServed(),
                new Lookups(local, forwarded, notFound),
                new Forwarding(askedPeers, forwarded.count(), successRate),
                remoteInstances());
    }

    private TimerStats timerStats(String source) {
        Timer timer = registry.find(FlightOpsMetrics.LOOKUPS).tag("source", source).timer();
        if (timer == null || timer.count() == 0) {
            return new TimerStats(0, null, null, null, null);
        }
        Double p50 = null;
        Double p95 = null;
        for (ValueAtPercentile v : timer.takeSnapshot().percentileValues()) {
            if (v.percentile() == 0.5) {
                p50 = round(v.value(TimeUnit.MILLISECONDS));
            } else if (v.percentile() == 0.95) {
                p95 = round(v.value(TimeUnit.MILLISECONDS));
            }
        }
        return new TimerStats(timer.count(), round(timer.mean(TimeUnit.MILLISECONDS)), p50, p95,
                round(timer.max(TimeUnit.MILLISECONDS)));
    }

    /** Requests answered by this instance, without actuator calls. */
    private Served requestsServed() {
        long total = 0;
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Timer t : registry.find("http.server.requests").timers()) {
            String uri = t.getId().getTag("uri");
            if (uri != null && uri.startsWith("/actuator")) {
                continue;
            }
            total += t.count();
            byStatus.merge(t.getId().getTag("status"), t.count(), Long::sum);
        }
        return new Served(total, byStatus);
    }

    private List<RemoteInstance> remoteInstances() {
        List<RemoteInstance> result = new ArrayList<>();
        for (EndpointHealth endpoint : healthRegistry.all()) {
            EndpointHealth.Snapshot s = endpoint.snapshot();
            Map<String, Long> byOutcome = new LinkedHashMap<>();
            long calls = 0;
            double totalMs = 0;
            for (Timer t : registry.find(FlightOpsMetrics.REMOTE_CALLS).tag("target", s.url()).timers()) {
                byOutcome.merge(t.getId().getTag("outcome"), t.count(), Long::sum);
                calls += t.count();
                totalMs += t.totalTime(TimeUnit.MILLISECONDS);
            }
            long failed = byOutcome.getOrDefault("failure", 0L) + byOutcome.getOrDefault("circuit_open", 0L);
            result.add(new RemoteInstance(s.group(), s.url(), s.healthy(), s.circuit(), calls, byOutcome,
                    calls == 0 ? null : round(100.0 * failed / calls),
                    calls == 0 ? null : round(totalMs / calls)));
        }
        return result;
    }

    private static Double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public record Metrics(String instance, Served requestsServed, Lookups lookupResponseTimes,
                          Forwarding forwarding, List<RemoteInstance> remoteInstances) {
    }

    public record Served(long total, Map<String, Long> byStatus) {
    }

    public record Lookups(TimerStats local, TimerStats forwarded, TimerStats notFound) {
    }

    /** Times in ms. medianMs is robust against a few slow calls (e.g. the first ones on a cold JVM); avgMs is not. */
    public record TimerStats(long count, Double avgMs, Double medianMs, Double p95Ms, Double maxMs) {
    }

    /** Lookups that were not local and had to ask the peers: how many found the flight. */
    public record Forwarding(long askedPeers, long foundOnPeer, Double successRatePercent) {
    }

    /** Peer health and failure rate (failure + circuit_open over all calls). */
    public record RemoteInstance(String group, String url, boolean healthy, EndpointHealth.State circuit,
                                 long calls, Map<String, Long> callsByOutcome, Double failureRatePercent,
                                 Double avgCallMs) {
    }
}
