package pt.isep.sidis.flightops.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.clients.*;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.domain.ScheduledFlight;
import pt.isep.sidis.flightops.peers.PeerClient;
import pt.isep.sidis.flightops.peers.PeerResult;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledFlightServiceTest {

    @Mock ScheduledFlightRepository repository;
    @Mock AircraftDirectory aircraftClient;
    @Mock RouteDirectory airportsRoutesClient;
    @Mock FlightQueryService flightQueryService;
    @Mock PeerClient peers;
    @Mock AircraftBookingLocks aircraftBookingLocks;

    @InjectMocks ScheduledFlightService service;

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

        FlightView result = service.scheduleFlight("route-opo-lis", "CS-TPA", dep, arr);

        assertThat(result.originIata()).isEqualTo("OPO");
        assertThat(result.destinationIata()).isEqualTo("LIS");
        assertThat(result.aircraftModel()).isEqualTo("A320neo");
        assertThat(result.fuelBurnRate()).isEqualTo(24000.0 / 6300.0);
        assertThat(result.status()).isEqualTo("SCHEDULED");
    }

    @Test
    void rejectsAircraftThatIsNotAvailable() {
        when(airportsRoutesClient.getRoute(any())).thenReturn(route);
        when(aircraftClient.getAircraft(any())).thenReturn(
                new AircraftInfo("CS-TPA", "UNDER_MAINTENANCE", "A320neo", 6300.0, 24000.0, 160));

        assertThatThrownBy(() -> service.scheduleFlight("route-opo-lis", "CS-TPA", dep, arr))
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

        assertThatThrownBy(() -> service.scheduleFlight("route-opo-lis", "CS-TPA", dep, arr))
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

        assertThatThrownBy(() -> service.scheduleFlight("route-opo-lis", "CS-TPA", dep, arr))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void cancelIsForwardedToThePeerThatOwnsTheFlight() {
        when(repository.findById("F-9")).thenReturn(Optional.empty());
        FlightView cancelled = new FlightView("F-9", "r", "CS-TPA", "A320neo", "OPO", "LIS",
                277.0, 3.8, dep, arr, "CANCELED");
        when(peers.patchOne(anyString(), eq(FlightView.class), eq("F-9")))
                .thenReturn(new PeerResult<>(List.of(cancelled), 0));

        assertThat(service.cancelFlight("F-9").status()).isEqualTo("CANCELED");
    }

    @Test
    void cancelLocalFlight() {
        ScheduledFlight f = new ScheduledFlight("r", "CS-TPA", "A320neo", "OPO", "LIS", 277.0, 3.8, dep, arr);
        when(repository.findById(f.getFlightNumber())).thenReturn(Optional.of(f));
        when(repository.save(f)).thenReturn(f);

        assertThat(service.cancelFlight(f.getFlightNumber()).status()).isEqualTo("CANCELED");
        verifyNoInteractions(peers);
    }

    @Test
    void cancelUnknownFlightIs404WhenAllPeersAnswered() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        when(peers.patchOne(anyString(), eq(FlightView.class), eq("nope"))).thenReturn(new PeerResult<>(List.of(), 0));

        assertThatThrownBy(() -> service.cancelFlight("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cancelUnknownFlightIs404WhenSomePeerIsDownAndSaysSo() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        when(peers.patchOne(anyString(), eq(FlightView.class), eq("nope"))).thenReturn(new PeerResult<>(List.of(), 1));

        assertThatThrownBy(() -> service.cancelFlight("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("1 peer instance(s) could not be reached");
    }
}
