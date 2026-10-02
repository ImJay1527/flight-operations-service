package pt.isep.sidis.flightops.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.clients.AircraftDirectory;
import pt.isep.sidis.flightops.clients.RouteDirectory;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.common.exceptions.ServiceUnavailableException;
import pt.isep.sidis.flightops.peers.PeerClient;
import pt.isep.sidis.flightops.peers.PeerResult;
import pt.isep.sidis.flightops.resilience.CircuitOpenException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Where a booking is made: on the first reachable instance holding the aircraft (owner, then backup), here as the
 * last resort. And cancelling across instances.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledFlightServiceTest {

    @Mock AircraftDirectory aircraftClient;
    @Mock RouteDirectory airportsRoutesClient;
    @Mock FlightQueryService flightQueryService;
    @Mock PeerClient peers;
    @Mock LocalBookingService localBookings;
    @Mock Cluster cluster;

    @InjectMocks ScheduledFlightService service;

    private final LocalDateTime dep = LocalDateTime.now().plusDays(3);
    private final LocalDateTime arr = dep.plusMinutes(45);

    private final FlightView booked = new FlightView("F-1", "route-opo-lis", "CS-TPC", "A320neo", "OPO", "LIS",
            277.0, 3.8, dep, arr, "SCHEDULED");

    /** This is instance1; the aircraft's flights are kept on the given instances, owner first. */
    private void heldBy(String... instances) {
        when(cluster.replicasOf("CS-TPC")).thenReturn(List.of(instances));
        when(cluster.self()).thenReturn("instance1");
    }

    private Booking book() {
        return service.scheduleFlight("route-opo-lis", "CS-TPC", dep, arr);
    }

    @Test
    void aircraftOwnedHereIsBookedHere() {
        heldBy("instance1", "instance2");
        when(localBookings.book("route-opo-lis", "CS-TPC", dep, arr)).thenReturn(booked);

        Booking booking = book();

        assertThat(booking.storedOn()).isEqualTo("instance1");
        assertThat(booking.replicas()).containsExactly("instance1", "instance2");
        verifyNoInteractions(peers);
    }

    @Test
    void aircraftOwnedByAPeerIsForwardedToIt() {
        heldBy("instance2", "instance1");
        when(peers.postTo(eq("instance2"), eq("/internal/flights"), any(), eq(FlightView.class))).thenReturn(booked);

        Booking booking = book();

        assertThat(booking.storedOn()).isEqualTo("instance2");
        assertThat(booking.flight()).isEqualTo(booked);
        verifyNoInteractions(localBookings);
    }

    @Test
    void ownersRefusalIsPassedOnWithItsMessage() {
        heldBy("instance2", "instance1");
        when(peers.postTo(any(), any(), any(), any())).thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT,
                "Conflict", null, "{\"error\":\"The aircraft is already scheduled...\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

        assertThatThrownBy(this::book)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The aircraft is already scheduled...");
        verifyNoInteractions(localBookings);
    }

    @Test
    void ownerUnreachableAndThisIsTheBackupMeansBookingHere() {
        // connection refused: the booking certainly didn't reach the owner, so booking here can't duplicate it
        heldBy("instance2", "instance1");
        when(peers.postTo(any(), any(), any(), any()))
                .thenThrow(new ResourceAccessException("I/O error", new ConnectException("Connection refused")));
        when(localBookings.book("route-opo-lis", "CS-TPC", dep, arr)).thenReturn(booked);

        assertThat(book().storedOn()).isEqualTo("instance1");
    }

    @Test
    void ownerWithOpenCircuitMeansTheBackupPeerBooksIt() {
        // three instances: this one doesn't hold CS-TPC, the owner is down, so its backup takes the booking
        heldBy("instance2", "instance3");
        when(peers.postTo(eq("instance2"), any(), any(), any())).thenThrow(new CircuitOpenException("https://instance2"));
        when(peers.postTo(eq("instance3"), any(), any(), eq(FlightView.class))).thenReturn(booked);

        assertThat(book().storedOn()).isEqualTo("instance3");
        verifyNoInteractions(localBookings);
    }

    @Test
    void noHolderReachableMeansBookingHere() {
        heldBy("instance2", "instance3");
        when(peers.postTo(any(), any(), any(), any())).thenThrow(new CircuitOpenException("down"));
        when(localBookings.book("route-opo-lis", "CS-TPC", dep, arr)).thenReturn(booked);

        Booking booking = book();

        assertThat(booking.storedOn()).isEqualTo("instance1");
        assertThat(booking.replicas()).containsExactly("instance2", "instance3");   // copied there once they're back
    }

    @Test
    void ownerThatTimedOutIs503NotASecondBooking() {
        // the owner may have stored it already: booking on the backup could create a duplicate
        heldBy("instance2", "instance1");
        when(peers.postTo(any(), any(), any(), any()))
                .thenThrow(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(this::book)
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("may or may not have been made");
        verifyNoInteractions(localBookings);
    }

    @Test
    void cancelIsForwardedToAPeerThatHasTheFlight() {
        when(localBookings.cancel("F-9")).thenReturn(null);
        FlightView cancelled = new FlightView("F-9", "r", "CS-TPA", "A320neo", "OPO", "LIS",
                277.0, 3.8, dep, arr, "CANCELED");
        when(peers.patchOne(anyString(), eq(FlightView.class), eq("F-9")))
                .thenReturn(new PeerResult<>(List.of(cancelled), 0));

        assertThat(service.cancelFlight("F-9").status()).isEqualTo("CANCELED");
    }

    @Test
    void cancelLocalFlight() {
        FlightView cancelled = new FlightView("F-1", "r", "CS-TPA", "A320neo", "OPO", "LIS",
                277.0, 3.8, dep, arr, "CANCELED", 2);
        when(localBookings.cancel("F-1")).thenReturn(cancelled);

        assertThat(service.cancelFlight("F-1").status()).isEqualTo("CANCELED");
        verifyNoInteractions(peers);
    }

    @Test
    void cancelUnknownFlightIs404WhenAllPeersAnswered() {
        when(peers.patchOne(anyString(), eq(FlightView.class), eq("nope"))).thenReturn(new PeerResult<>(List.of(), 0));

        assertThatThrownBy(() -> service.cancelFlight("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cancelUnknownFlightIs404WhenSomePeerIsDownAndSaysSo() {
        when(peers.patchOne(anyString(), eq(FlightView.class), eq("nope"))).thenReturn(new PeerResult<>(List.of(), 1));

        assertThatThrownBy(() -> service.cancelFlight("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("1 peer instance(s) could not be reached");
    }
}
