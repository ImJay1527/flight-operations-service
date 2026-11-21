package pt.isep.sidis.flightops.monitoring;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.messaging.MessagingStatus;
import pt.isep.sidis.flightops.readmodel.ReadModel;
import pt.isep.sidis.flightops.readmodel.ReadModelState;
import pt.isep.sidis.flightops.replication.ReplicationSender;

import java.util.List;
import java.util.Map;
import pt.isep.sidis.flightops.resilience.EndpointHealth;
import pt.isep.sidis.flightops.resilience.HealthRegistry;

/** What this instance currently knows about its peers and the other services' instances. */
@RestController
@RequestMapping("/api/cluster")
@Tag(name = "Cluster", description = "Health and circuit breaker state of every peer / remote instance (PL3 p.12, p.14, p.19)")
public class ClusterHealthController {

    private final HealthRegistry registry;
    private final Cluster cluster;
    private final ReplicationSender replication;
    private final ReadModel readModel;
    private final MessagingStatus messaging;
    private final String self;

    public ClusterHealthController(HealthRegistry registry, Cluster cluster, ReplicationSender replication,
                                   ReadModel readModel, MessagingStatus messaging,
                                   @Value("${spring.application.name}") String name,
                                   @Value("${server.port}") String port) {
        this.registry = registry;
        this.cluster = cluster;
        this.replication = replication;
        this.readModel = readModel;
        this.messaging = messaging;
        this.self = name + ":" + port;
    }

    @Operation(summary = "Health of the peers and remote instances, as seen by this instance, and the copies of "
            + "flights still waiting to be sent to each peer (replicationBacklog), and the state of this instance's "
            + "CQRS read model (readModel) and its message broker connection (messaging)")
    @PreAuthorize("hasAnyRole('ADMIN', 'ATCC', 'BACKOFFICE_OPERATOR')")
    @GetMapping("/health")
    public ClusterHealth health() {
        return new ClusterHealth(self, cluster.self(), cluster.isQueryOnly() ? QUERIES_ONLY : COMMANDS_AND_QUERIES,
                cluster.members().stream().map(Cluster.Member::name).toList(), registry.snapshot(),
                cluster.replicationFactor(), replication.backlog(), readModel.state().snapshot(), messaging.snapshot());
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

    /** What an instance does: a command instance (the usual one) answers queries too; a query instance only queries. */
    public static final String COMMANDS_AND_QUERIES = "commands and queries";
    public static final String QUERIES_ONLY = "queries only";

    /**
     * @param instance        service name and port
     * @param name            instance name ({@code flightops.instance-name})
     * @param role            {@link #COMMANDS_AND_QUERIES} or {@link #QUERIES_ONLY} (CQRS with separate processes)
     * @param members         the instances that hold the flights (a query instance is never one of them)
     */
    public record ClusterHealth(String instance, String name, String role, List<String> members,
                                List<EndpointHealth.Snapshot> endpoints, int replicationFactor,
                                Map<String, Long> replicationBacklog, ReadModelState.Snapshot readModel,
                                MessagingStatus.Snapshot messaging) {
    }

    public record Owner(String registration, String owner, String ownerUrl, List<String> replicas,
                        List<String> instances) {
    }
}
