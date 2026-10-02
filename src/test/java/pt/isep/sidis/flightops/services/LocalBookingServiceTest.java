package pt.isep.sidis.flightops.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.clients.*;
import pt.isep.sidis.flightops.domain.ScheduledFlight;
import pt.isep.sidis.flightops.replication.ReplicationOutbox;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The scheduling rules and local changes, on the instance that makes them (LocalBookingService). */
@ExtendWith(MockitoExtension.class)
class LocalBookingServiceTest {

    @Mock ScheduledFlightRepository repository;
    @Mock AircraftDirectory aircraftClient;
    @Mock RouteDirectory airportsRoutesClient;
    @Mock FlightQueryService flightQueryService;
    @Mock AircraftBookingLocks aircraftBookingLocks;
    @Mock ReplicationOutbox replicationOutbox;

    @InjectMocks LocalBookingService service;

    private final LocalDateTime dep = LocalDateTime.now().plusDays(3);
    private final LocalDateTime arr = dep.plusMinutes(45);

    private RouteInfo route;
    private AircraftInfo aircraft;

    @BeforeEach
    void setUp() {
        AirportInfo opo = new AirportInfo("OPO", "OPERATIONAL", List.of("A320neo"));
        AirportInfo lis = new AirportInfo("LIS", "OPERATIONAL", List.of("A320neo"));
        route = new RouteInfo("route-opo-lis", "ACTIVE", 277.0, 350.0, 100, opo, lis);
        aircraft = new AircraftInfo("CS-TPA", "AVAILABLE", "A320neo", 6300.0, 24000.0, 160);
    }

    @Test
    void schedulesFlightWithSnapshotOfRemoteData() {
        when(airportsRoutesClient.getRoute("route-opo-lis")).thenReturn(route);
        when(aircraftClient.getAircraft("CS-TPA")).thenReturn(aircraft);
        when(repository.findOverlappingFlightsWithLock(eq("CS-TPA"), any(), any())).thenReturn(List.of());
        when(flightQueryService.activeOnPeers("CS-TPA")).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlightView result = service.book("route-opo-lis", "CS-TPA", dep, arr);

        assertThat(result.originIata()).isEqualTo("OPO");
        assertThat(result.destinationIata()).isEqualTo("LIS");
        assertThat(result.aircraftModel()).isEqualTo("A320neo");
        assertThat(result.fuelBurnRate()).isEqualTo(24000.0 / 6300.0);
        assertThat(result.status()).isEqualTo("SCHEDULED");
        assertThat(result.revision()).isEqualTo(1);
    }

    @Test
    void newBookingIsQueuedForItsOtherCopies() {
        when(airportsRoutesClient.getRoute("route-opo-lis")).thenReturn(route);
        when(aircraftClient.getAircraft("CS-TPA")).thenReturn(aircraft);
        when(repository.findOverlappingFlightsWithLock(eq("CS-TPA"), any(), any())).thenReturn(List.of());
        when(flightQueryService.activeOnPeers("CS-TPA")).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlightView result = service.book("route-opo-lis", "CS-TPA", dep, arr);

        verify(replicationOutbox).flightChanged(argThat(f -> f.getFlightNumber().equals(result.flightNumber())));
    }

    @Test
    void cancelRaisesTheRevisionAndIsQueuedForTheOtherCopies() {
        ScheduledFlight f = new ScheduledFlight("r", "CS-TPA", "A320neo", "OPO", "LIS", 277.0, 3.8, dep, arr);
        when(repository.findById(f.getFlightNumber())).thenReturn(Optional.of(f));
        when(repository.save(f)).thenReturn(f);

        FlightView result = service.cancel(f.getFlightNumber());

        assertThat(result.status()).isEqualTo("CANCELED");
        assertThat(result.revision()).isEqualTo(2);
        verify(replicationOutbox).flightChanged(f);
    }

    @Test
    void cancelOfAFlightNotHereReturnsNull() {
        when(repository.findById("elsewhere")).thenReturn(Optional.empty());

        assertThat(service.cancel("elsewhere")).isNull();
        verifyNoInteractions(replicationOutbox);
    }

    @Test
    void rejectsAircraftThatIsNotAvailable() {
        when(airportsRoutesClient.getRoute(any())).thenReturn(route);
        when(aircraftClient.getAircraft(any())).thenReturn(
                new AircraftInfo("CS-TPA", "UNDER_MAINTENANCE", "A320neo", 6300.0, 24000.0, 160));

        assertThatThrownBy(() -> service.book("route-opo-lis", "CS-TPA", dep, arr))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNDER_MAINTENANCE");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsAirportNotCertifiedForModel() {
        AirportInfo lis = new AirportInfo("LIS", "OPERATIONAL", List.of("737 MAX"));
        when(airportsRoutesClient.getRoute(any())).thenReturn(
                new RouteInfo("route-opo-lis", "ACTIVE", 277.0, 350.0, 100, route.origin(), lis));
        when(aircraftClient.getAircraft(any())).thenReturn(aircraft);

        assertThatThrownBy(() -> service.book("route-opo-lis", "CS-TPA", dep, arr))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destination airport (LIS)");
    }

    @Test
    void rejectsOverlapWithFlightStoredOnAnotherReplica() {
        when(airportsRoutesClient.getRoute(any())).thenReturn(route);
        when(aircraftClient.getAircraft(any())).thenReturn(aircraft);
        when(repository.findOverlappingFlightsWithLock(any(), any(), any())).thenReturn(List.of());
        FlightView onPeer = new FlightView("F-1", "route-lis-mad", "CS-TPA", "A320neo", "LIS", "MAD",
                502.0, 3.8, dep.plusMinutes(10), dep.plusMinutes(90), "SCHEDULED");
        when(flightQueryService.activeOnPeers("CS-TPA")).thenReturn(List.of(onPeer));

        assertThatThrownBy(() -> service.book("route-opo-lis", "CS-TPA", dep, arr))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

}
