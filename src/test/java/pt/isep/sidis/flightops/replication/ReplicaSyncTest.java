package pt.isep.sidis.flightops.replication;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.cluster.DnsDiscovery;
import pt.isep.sidis.flightops.domain.ScheduledFlight;
import pt.isep.sidis.flightops.common.metrics.FlightOpsMetrics;
import pt.isep.sidis.flightops.peers.PeerClient;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;
import pt.isep.sidis.flightops.resilience.HealthRegistry;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Rebalancing on Kubernetes: flights this instance no longer holds go to their new holders. */
class ReplicaSyncTest {

    private static final Set<String> PODS = Set.of("flightops-0", "flightops-1", "flightops-2", "flightops-3");

    private final PeerClient peers = mock(PeerClient.class);
    private final ScheduledFlightRepository flights = mock(ScheduledFlightRepository.class);
    private final Cluster cluster = new Cluster("flightops-0", List.of(), List.of(), 2,
            new DnsDiscovery("https://flightops-{i}.flightops-peers:8083", 10,
                    host -> PODS.contains(host.substring(0, host.indexOf('.')))), 0);
    private final HealthRegistry healthRegistry = new HealthRegistry(3, 10_000, 60_000,
            new FlightOpsMetrics(new SimpleMeterRegistry()));
    private final ReplicaSync sync = new ReplicaSync(cluster, peers, mock(ReplicaStore.class), flights, healthRegistry,
            0, 60_000, 0);

    /** An aircraft that flightops-0 holds, or does not hold, with 4 pods. */
    private String aircraft(boolean heldByThisInstance) {
        return IntStream.range(0, 1000).mapToObj(i -> "CS-" + i)
                .filter(reg -> cluster.holdsLocally(reg) == heldByThisInstance)
                .findFirst().orElseThrow();
    }

    private static ScheduledFlight flight(String registration) {
        LocalDateTime departure = LocalDateTime.now().plusDays(3);
        return new ScheduledFlight("OPO-LIS", registration, "A320neo", "OPO", "LIS", 277.0, 2.5,
                departure, departure.plusMinutes(45));
    }

    @Test
    void aFlightThisInstanceNoLongerHoldsIsSentToItsHoldersThenDeleted() {
        ScheduledFlight moved = flight(aircraft(false));
        ScheduledFlight kept = flight(aircraft(true));
        when(flights.findAll()).thenReturn(List.of(moved, kept));

        assertThat(sync.handOff()).isEqualTo(1);

        for (String holder : cluster.replicasOf(moved.getAircraftRegistration())) {
            verify(peers).pushCopy(eq(holder), any(FlightView.class));
        }
        verify(flights).delete(moved);
        verify(flights, never()).delete(kept);
    }

    @Test
    void aFlightIsKeptIfAHolderCannotBeReached() {
        ScheduledFlight moved = flight(aircraft(false));
        when(flights.findAll()).thenReturn(List.of(moved));
        String holder = cluster.replicasOf(moved.getAircraftRegistration()).get(1);
        doThrow(new ResourceAccessException("down")).when(peers).pushCopy(eq(holder), any(FlightView.class));

        assertThat(sync.handOff()).isZero();
        verify(flights, never()).delete(any(ScheduledFlight.class));
    }

    @Test
    void whenANewInstanceStartsAnsweringTheHandOffRunsAtOnce() {
        sync.run(null);   // startup catch-up: asks the 3 peers once
        verify(peers, times(3)).copiesFor(anyString(), eq("flightops-0"));

        // flightops-3 (added by the auto-scaler) answers its first health check: no wait for the 60-s run
        healthRegistry.register(PeerClient.PEER_GROUP, "https://flightops-3.flightops-peers:8083").recordSuccess();

        verify(peers, timeout(2000).times(6)).copiesFor(anyString(), eq("flightops-0"));
        verify(flights, timeout(2000).times(2)).findAll();   // and the hand-off ran again
        sync.shutdown();
    }
}
