package pt.isep.sidis.flightops.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import pt.isep.sidis.flightops.monitoring.FlightOpsMetrics;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import pt.isep.sidis.flightops.api.dto.FlightView;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightQueryServiceTest {

    @Mock ScheduledFlightRepository repository;
    @Mock PeerClient peers;
    @Spy FlightOpsMetrics metrics = new FlightOpsMetrics(new SimpleMeterRegistry());

    @InjectMocks FlightQueryService service;

    private final LocalDateTime t0 = LocalDateTime.now().minusDays(10);

    @Test
    @SuppressWarnings("unchecked")
    void mergesLocalAndPeerResultsWithoutDuplicatesOrderedByDeparture() {
        ScheduledFlight local = new ScheduledFlight("r", "CS-TPA", "A320neo", "OPO", "LIS", 277, 3.8, t0.plusDays(2), t0.plusDays(2).plusHours(1));
        FlightView localView = FlightView.of(local);
        FlightView remote = new FlightView("REMOTE-1", "r", "CS-TPA", "A320neo", "OPO", "LIS", 277, 3.8,
                t0, t0.plusHours(1), "SCHEDULED");

        when(repository.findByAircraftRegistration("CS-TPA")).thenReturn(List.of(local));
        // the peer also returns a copy of the local flight: must not appear twice
        when(peers.getList(anyString(), any(ParameterizedTypeReference.class), eq("CS-TPA")))
                .thenReturn(new PeerResult<>(List.of(remote, localView), 0));

        List<FlightView> result = service.findByAircraft("CS-TPA");

        assertThat(result).extracting(FlightView::flightNumber)
                .containsExactly("REMOTE-1", local.getFlightNumber());
    }

    @Test
    void findByIdAsksPeersWhenNotLocal() {
        FlightView remote = new FlightView("F-1", "r", "CS-TPA", "A320neo", "OPO", "LIS", 277, 3.8,
                t0, t0.plusHours(1), "SCHEDULED");
        when(repository.findById("F-1")).thenReturn(Optional.empty());
        when(peers.getOne(anyString(), eq(FlightView.class), eq("F-1"))).thenReturn(new PeerResult<>(List.of(remote), 0, "http://peer-2"));

        FlightLookup lookup = service.findById("F-1");
        assertThat(lookup.flight()).isEqualTo(remote);
        assertThat(lookup.source()).isEqualTo("peer:http://peer-2");
    }

    @Test
    void findByIdIs404WhenNoPeerHasIt() {
        when(repository.findById("F-1")).thenReturn(Optional.empty());
        when(peers.getOne(anyString(), eq(FlightView.class), eq("F-1"))).thenReturn(new PeerResult<>(List.of(), 0));

        assertThatThrownBy(() -> service.findById("F-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Scheduled flight not found with number: F-1");
    }

    @Test
    void findByIdIs404WhenAPeerIsDownAndSaysSo() {
        // PL3 p.11 / p.16 test 03: stop instance 2, GET remote-only data on instance 1 -> 404
        when(repository.findById("F-1")).thenReturn(Optional.empty());
        when(peers.getOne(anyString(), eq(FlightView.class), eq("F-1"))).thenReturn(new PeerResult<>(List.of(), 1));

        assertThatThrownBy(() -> service.findById("F-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("1 peer instance(s) could not be reached");
    }
}
