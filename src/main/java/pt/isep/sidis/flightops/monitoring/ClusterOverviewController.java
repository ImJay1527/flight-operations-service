package pt.isep.sidis.flightops.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.domain.ScheduledFlight;
import pt.isep.sidis.flightops.peers.PeerClient;
import pt.isep.sidis.flightops.replication.ReplicationSender;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;
import pt.isep.sidis.flightops.resilience.HealthRegistry;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import pt.isep.sidis.flightops.common.metrics.FlightOpsMetrics;

/**
 * The data behind the dashboard (/dashboard.html): every instance's status in one answer. Like a list query it is a
 * scatter-gather: the instance that receives the request adds its own status to its peers' (asked in parallel on
 * /internal/cluster/status, which answers for that instance only), and a peer that doesn't answer is shown as
 * unreachable.
 */
@RestController
@Tag(name = "Cluster")
public class ClusterOverviewController {

    private final Cluster cluster;
    private final PeerClient peers;
    private final ScheduledFlightRepository flights;
    private final HealthRegistry healthRegistry;
    private final ReplicationSender replication;
    private final MeterRegistry registry;
    private final CpuSampler cpu;

    public ClusterOverviewController(Cluster cluster, PeerClient peers, ScheduledFlightRepository flights,
                                     HealthRegistry healthRegistry, ReplicationSender replication,
                                     FlightOpsMetrics metrics, CpuSampler cpu) {
        this.cluster = cluster;
        this.peers = peers;
        this.flights = flights;
        this.healthRegistry = healthRegistry;
        this.replication = replication;
        this.registry = metrics.registry();
        this.cpu = cpu;
    }

    public record Overview(String answeredBy, Instant generatedAt, List<InstanceStatus> instances) {
    }

    @Operation(summary = "Status of every instance, gathered from all of them (for the dashboard)")
    @PreAuthorize("hasAnyRole('ADMIN', 'ATCC', 'BACKOFFICE_OPERATOR')")
    @GetMapping("/api/cluster/overview")
    public Overview overview() {
        List<InstanceStatus> all = new ArrayList<>();
        all.add(status());
        peers.getFromEach("/internal/cluster/status", InstanceStatus.class).forEach((name, status) ->
                all.add(status != null ? status : InstanceStatus.unreachable(name)));
        all.sort(Comparator.comparing(InstanceStatus::instance));
        return new Overview(cluster.self(), Instant.now(), all);
    }

    @Operation(summary = "Status of this instance only (service-to-service, used by /api/cluster/overview)")
    @GetMapping("/internal/cluster/status")
    @Transactional(readOnly = true)
    public InstanceStatus status() {
        Map<String, List<ScheduledFlight>> byAircraft = flights.findAll().stream()
                .collect(Collectors.groupingBy(ScheduledFlight::getAircraftRegistration, TreeMap::new,
                        Collectors.toList()));
        List<InstanceStatus.AircraftRole> aircraft = new ArrayList<>();
        long owner = 0;
        long backup = 0;
        long handOff = 0;
        for (Map.Entry<String, List<ScheduledFlight>> entry : byAircraft.entrySet()) {
            InstanceStatus.Role role = cluster.ownsLocally(entry.getKey()) ? InstanceStatus.Role.OWNER
                    : cluster.holdsLocally(entry.getKey()) ? InstanceStatus.Role.BACKUP : InstanceStatus.Role.HAND_OFF;
            long count = entry.getValue().size();
            aircraft.add(new InstanceStatus.AircraftRole(entry.getKey(), role, count));
            switch (role) {
                case OWNER -> owner += count;
                case BACKUP -> backup += count;
                case HAND_OFF -> handOff += count;
            }
        }
        Runtime runtime = Runtime.getRuntime();
        return new InstanceStatus(cluster.self(), true,
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()),
                cluster.members().stream().map(Cluster.Member::name).toList(),
                cluster.replicationFactor(), owner, backup, handOff, aircraft, requestsServed(), cpu.percent(),
                (runtime.totalMemory() - runtime.freeMemory()) >> 20, runtime.maxMemory() >> 20,
                healthRegistry.snapshot(), replication.backlog(), cluster.recentChanges());
    }

    /** Client requests answered by this instance: not the dashboard, health checks or monitoring themselves. */
    private long requestsServed() {
        long total = 0;
        for (Timer t : registry.find("http.server.requests").timers()) {
            String uri = t.getId().getTag("uri");
            if (uri == null || uri.startsWith("/actuator") || uri.startsWith("/api/cluster")
                    || uri.startsWith("/internal/cluster") || uri.startsWith("/api/auth")) {
                continue;
            }
            total += t.count();
        }
        return total;
    }

}
