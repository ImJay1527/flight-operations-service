package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.monitoring.FlightOpsMetrics;
import pt.isep.sidis.flightops.peers.PeerClient;
import pt.isep.sidis.flightops.peers.PeerResult;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads: answered from this instance's data first, then completed with what the peers hold, so the client gets the
 * same answer whichever instance it asks.
 */
@Service
@RequiredArgsConstructor
public class FlightQueryService {

    private static final ParameterizedTypeReference<List<FlightView>> FLIGHT_LIST = new ParameterizedTypeReference<>() {};

    private final ScheduledFlightRepository repository;
    private final PeerClient peers;
    private final FlightOpsMetrics metrics;

    // local data only - used by the /internal endpoints

    @Transactional(readOnly = true)
    public FlightView localById(String flightNumber) {
        return repository.findById(flightNumber).map(FlightView::of).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<FlightView> localByAircraft(String registration) {
        return repository.findByAircraftRegistration(registration).stream().map(FlightView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<FlightView> localActive(String registration) {
        return repository.findNonCancelledFlights(registration).stream().map(FlightView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<FlightView> localDepartures(String originIata, LocalDateTime from, LocalDateTime to) {
        return repository.findUpcomingDepartures(originIata, from, to).stream().map(FlightView::of).toList();
    }

    // local + peers

    /** PL3 p.11: local store first; if not there, ask the peers one by one; first answer wins. */
    public FlightLookup findById(String flightNumber) {
        long start = System.nanoTime();
        FlightView local = localById(flightNumber);
        if (local != null) {
            metrics.recordLookup(FlightOpsMetrics.LOCAL, start);
            return FlightLookup.local(local);
        }
        PeerResult<FlightView> remote = peers.getOne("/internal/flights/{n}", FlightView.class, flightNumber);
        if (!remote.items().isEmpty()) {
            metrics.recordLookup(FlightOpsMetrics.FORWARDED, start);
            return FlightLookup.fromPeer(remote.items().get(0), remote.answeredBy());
        }
        metrics.recordLookup(FlightOpsMetrics.NOT_FOUND, start);
        // 404 as in PL3 p.11; the message says when a peer was unreachable, since the flight may be there
        throw new ResourceNotFoundException(remote.notFoundMessage("Scheduled flight not found with number: " + flightNumber));
    }

    public List<FlightView> findByAircraft(String registration) {
        PeerResult<FlightView> remote = peers.getList("/internal/flights?aircraft={reg}", FLIGHT_LIST, registration);
        return merge(localByAircraft(registration), remote.items());
    }

    /** Non-cancelled flights (optionally of one aircraft) on all instances. */
    public List<FlightView> findActive(String registrationOrNull) {
        return merge(localActive(registrationOrNull), activeOnPeers(registrationOrNull));
    }

    /** Non-cancelled flights on the peers only. */
    public List<FlightView> activeOnPeers(String registrationOrNull) {
        PeerResult<FlightView> remote = registrationOrNull == null
                ? peers.getList("/internal/flights/active", FLIGHT_LIST)
                : peers.getList("/internal/flights/active?aircraft={reg}", FLIGHT_LIST, registrationOrNull);
        return remote.items();
    }

    public List<FlightView> findUpcomingDepartures(String originIata, int hours) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = now.plusHours(hours);
        PeerResult<FlightView> remote = peers.getList("/internal/flights/departures/{iata}?hours={h}",
                FLIGHT_LIST, originIata, hours);
        return merge(localDepartures(originIata, now, end), remote.items());
    }

    /** Union without duplicates, by departure time. */
    private List<FlightView> merge(List<FlightView> local, List<FlightView> remote) {
        Map<String, FlightView> byNumber = new LinkedHashMap<>();
        local.forEach(f -> byNumber.put(f.flightNumber(), f));
        remote.forEach(f -> byNumber.putIfAbsent(f.flightNumber(), f));
        return byNumber.values().stream()
                .sorted(Comparator.comparing(FlightView::scheduledDeparture))
                .toList();
    }
}
