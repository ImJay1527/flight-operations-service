package pt.isep.sidis.flightops.resilience;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** What this instance currently knows about its peers and the other services' instances. */
@RestController
@RequestMapping("/api/cluster")
@Tag(name = "Cluster", description = "Health and circuit breaker state of every peer / remote instance (PL3 p.12, p.14, p.19)")
public class ClusterHealthController {

    private final HealthRegistry registry;
    private final String self;

    public ClusterHealthController(HealthRegistry registry,
                                   @Value("${spring.application.name}") String name,
                                   @Value("${server.port}") String port) {
        this.registry = registry;
        this.self = name + ":" + port;
    }

    @Operation(summary = "Health of the peers and remote instances, as seen by this instance")
    @PreAuthorize("hasAnyRole('ADMIN', 'ATCC', 'BACKOFFICE_OPERATOR')")
    @GetMapping("/health")
    public ClusterHealth health() {
        return new ClusterHealth(self, registry.snapshot());
    }

    public record ClusterHealth(String instance, List<EndpointHealth.Snapshot> endpoints) {
    }
}
