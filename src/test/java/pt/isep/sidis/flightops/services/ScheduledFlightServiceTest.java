package pt.isep.sidis.flightops.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.clients.*;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.common.exceptions.ServiceUnavailableException;
import pt.isep.sidis.flightops.resilience.CircuitOpenException;
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

/** Where a booking is made (sharding by aircraft) and cancelling across instances. */
@ExtendWith(MockitoExtension.class)
class ScheduledFlightServiceTest {

    @Mock ScheduledFlightRepository repository;
    @Mock AircraftDirectory aircraftClient;
    @Mock RouteDirectory airportsRoutesClient;
    @Mock FlightQueryService flightQueryService;
    @Mock PeerClient peers;
    @Mock LocalBookingService localBookings;
    @Mock Cluster cluster;

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

    private final FlightView booked = new FlightView("F-1", "route-opo-lis", "CS-TPC", "A320neo", "OPO", "LIS",
            277.0, 3.8, dep, arr, "SCHEDULED");

    @Test
    void aircraftOwnedHereIsBookedHere() {
        when(cluster.ownerOf("CS-TPC")).thenReturn("instance1");
        when(cluster.self()).thenReturn("instance1");
        when(localBookings.book("route-opo-lis", "CS-TPC", dep, arr)).thenReturn(booked);

        Booking booking = service.scheduleFlight("route-opo-lis", "CS-TPC", dep, arr);

        assertThat(booking.storedOn()).isEqualTo("instance1");
        verifyNoInteractions(peers);
    }

    @Test
    void aircraftOwnedByAPeerIsForwardedToIt() {
        when(cluster.ownerOf("CS-TPC")).thenReturn("instance2");
        when(cluster.self()).thenReturn("instance1");
        when(peers.postTo(eq("instance2"), eq("/internal/flights"), any(), eq(FlightView.class))).thenReturn(booked);

        Booking booking = service.scheduleFlight("route-opo-lis", "CS-TPC", dep, arr);

        assertThat(booking.storedOn()).isEqualTo("instance2");
        assertThat(booking.flight()).isEqualTo(booked);
        verifyNoInteractions(localBookings);
    }

    @Test
    void ownersRefusalIsPassedOnWithItsMessage() {
        when(cluster.ownerOf("CS-TPC")).thenReturn("instance2");
        when(cluster.self()).thenReturn("instance1");
        when(peers.postTo(any(), any(), any(), any())).thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT,
                "Conflict", null, "{\"error\":\"The aircraft is already scheduled...\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.scheduleFlight("route-opo-lis", "CS-TPC", dep, arr))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The aircraft is already scheduled...");
        verifyNoInteractions(localBookings);
    }

    @Test
    void ownerThatCannotBeReachedMeansBookingHere() {
        // connection refused: the booking certainly didn't reach the owner, so booking here can't duplicate it
        when(cluster.ownerOf("CS-TPC")).thenReturn("instance2");
        when(cluster.self()).thenReturn("instance1");
        when(peers.postTo(any(), any(), any(), any()))
                .thenThrow(new ResourceAccessException("I/O error", new ConnectException("Connection refused")));
        when(localBookings.book("route-opo-lis", "CS-TPC", dep, arr)).thenReturn(booked);

        assertThat(service.scheduleFlight("route-opo-lis", "CS-TPC", dep, arr).storedOn()).isEqualTo("instance1");
    }

    @Test
    void ownerWithOpenCircuitMeansBookingHere() {
        when(cluster.ownerOf("CS-TPC")).thenReturn("instance2");
        when(cluster.self()).thenReturn("instance1");
        when(peers.postTo(any(), any(), any(), any())).thenThrow(new CircuitOpenException("https://instance2"));
        when(localBookings.book("route-opo-lis", "CS-TPC", dep, arr)).thenReturn(booked);

        assertThat(service.scheduleFlight("route-opo-lis", "CS-TPC", dep, arr).storedOn()).isEqualTo("instance1");
    }

    @Test
    void ownerThatTimedOutIs503NotASecondBooking() {
        // the owner may have stored it already: booking here could create a duplicate
        when(cluster.ownerOf("CS-TPC")).thenReturn("instance2");
        when(cluster.self()).thenReturn("instance1");
        when(peers.postTo(any(), any(), any(), any()))
                .thenThrow(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> service.scheduleFlight("route-opo-lis", "CS-TPC", dep, arr))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("may or may not have been made");
        verifyNoInteractions(localBookings);
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
