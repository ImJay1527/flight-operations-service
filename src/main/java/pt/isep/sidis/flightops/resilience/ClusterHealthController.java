package pt.isep.sidis.flightops.resilience;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.replication.ReplicationSender;

import java.util.List;
import java.util.Map;

/** What this instance currently knows about its peers and the other services' instances. */
@RestController
@RequestMapping("/api/cluster")
@Tag(name = "Cluster", description = "Health and circuit breaker state of every peer / remote instance (PL3 p.12, p.14, p.19)")
public class ClusterHealthController {

    private final HealthRegistry registry;
    private final Cluster cluster;
    private final ReplicationSender replication;
    private final String self;

    public ClusterHealthController(HealthRegistry registry, Cluster cluster, ReplicationSender replication,
                                   @Value("${spring.application.name}") String name,
                                   @Value("${server.port}") String port) {
        this.registry = registry;
        this.cluster = cluster;
        this.replication = replication;
        this.self = name + ":" + port;
    }

    @Operation(summary = "Health of the peers and remote instances, as seen by this instance, and the copies of "
            + "flights still waiting to be sent to each peer (replicationBacklog)")
    @PreAuthorize("hasAnyRole('ADMIN', 'ATCC', 'BACKOFFICE_OPERATOR')")
    @GetMapping("/health")
    public ClusterHealth health() {
        return new ClusterHealth(self, registry.snapshot(), cluster.replicationFactor(), replication.backlog());
    }

    @Operation(summary = "Which instances hold an aircraft's flights (sharding by registration, P1 p.15, plus "
            + "replication): the owner, where bookings go, and its backups")
    @PreAuthorize("hasAnyRole('ADMIN', 'ATCC', 'BACKOFFICE_OPERATOR')")
    @GetMapping("/owner/{registration}")
    public Owner owner(@PathVariable String registration) {
        String owner = cluster.ownerOf(registration);
        return new Owner(registration.toUpperCase(), owner, cluster.member(owner).map(Cluster.Member::url).orElse(null),
                cluster.replicasOf(registration), cluster.members().stream().map(Cluster.Member::name).toList());
    }

    public record ClusterHealth(String instance, List<EndpointHealth.Snapshot> endpoints, int replicationFactor,
                                Map<String, Long> replicationBacklog) {
    }

    public record Owner(String registration, String owner, String ownerUrl, List<String> replicas,
                        List<String> instances) {
    }
}
