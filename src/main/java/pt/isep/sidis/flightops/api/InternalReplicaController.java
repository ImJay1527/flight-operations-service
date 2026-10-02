package pt.isep.sidis.flightops.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.replication.ReplicaStore;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;

import java.util.List;

/** Replication between the instances of this service (role SERVICE). */
@RestController
@RequestMapping("/internal/replicas")
@Tag(name = "Internal (instance-to-instance)", description = "Local-data endpoints used by the other instances. Role SERVICE only.")
public class InternalReplicaController {

    private final ReplicaStore store;
    private final ScheduledFlightRepository flights;
    private final Cluster cluster;

    public InternalReplicaController(ReplicaStore store, ScheduledFlightRepository flights, Cluster cluster) {
        this.store = store;
        this.flights = flights;
        this.cluster = cluster;
    }

    @Operation(summary = "Store or update this instance's copy of a flight (sent by the instance that changed it). "
            + "Kept only if newer than the copy already here; never forwarded.")
    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody FlightView copy) {
        store.apply(copy);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "The flights on this instance that the given instance should also hold (catch-up after a "
            + "restart, and the periodic repair)")
    @GetMapping
    @Transactional(readOnly = true)
    public List<FlightView> copiesFor(@RequestParam("for") String instance) {
        return flights.findAll().stream()
                .filter(f -> cluster.replicasOf(f.getAircraftRegistration()).contains(instance))
                .map(FlightView::of)
                .toList();
    }
}
