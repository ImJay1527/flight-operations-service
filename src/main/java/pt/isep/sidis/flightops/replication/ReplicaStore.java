package pt.isep.sidis.flightops.replication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.domain.FlightStatus;
import pt.isep.sidis.flightops.domain.ScheduledFlight;
import pt.isep.sidis.flightops.monitoring.FlightOpsMetrics;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;
import pt.isep.sidis.flightops.services.LocalBookingService;

import java.time.LocalDateTime;

/**
 * Applies copies of flights received from other instances. New flights are stored; known ones take over the copy's
 * state if it is newer ({@link ScheduledFlight#updateFromCopy}). Applying the same copy twice changes nothing, and
 * the order copies arrive in doesn't matter: all instances end up with the same state (eventual consistency).
 * Received copies are not forwarded again: the instance that made the change sends them to every holder.
 */
@Service
public class ReplicaStore {

    private static final Logger log = LoggerFactory.getLogger(ReplicaStore.class);

    private final ScheduledFlightRepository flights;
    private final FlightOpsMetrics metrics;

    public ReplicaStore(ScheduledFlightRepository flights, FlightOpsMetrics metrics) {
        this.flights = flights;
        this.metrics = metrics;
    }

    /** True if this instance's data changed. */
    @Transactional
    public boolean apply(FlightView copy) {
        FlightStatus status = FlightStatus.valueOf(copy.status());
        ScheduledFlight existing = flights.findById(copy.flightNumber()).orElse(null);
        if (existing != null) {
            boolean changed = existing.updateFromCopy(status, copy.revision());
            if (changed) {
                metrics.recordReplication("applied");
            }
            return changed;
        }
        ScheduledFlight flight = new ScheduledFlight(copy.flightNumber(), copy.routeId(), copy.aircraftRegistration(),
                copy.aircraftModel(), copy.originIata(), copy.destinationIata(), copy.distanceKm(), copy.fuelBurnRate(),
                copy.scheduledDeparture(), copy.scheduledArrival());
        flight.restoreCopyState(status, copy.revision());
        if (status == FlightStatus.SCHEDULED) {
            reportOverlaps(flight);
        }
        flights.save(flight);
        metrics.recordReplication("applied");
        return true;
    }

    /**
     * Two instances can each accept a booking for the same aircraft while they cannot reach each other (a network
     * partition: AP keeps both available). Both bookings are kept, and the conflict is logged so that someone can
     * cancel one of them.
     */
    private void reportOverlaps(ScheduledFlight incoming) {
        LocalDateTime from = incoming.getScheduledDeparture().minusMinutes(LocalBookingService.TURNAROUND_BUFFER_MINUTES);
        LocalDateTime to = incoming.getScheduledArrival().plusMinutes(LocalBookingService.TURNAROUND_BUFFER_MINUTES);
        flights.findByAircraftRegistration(incoming.getAircraftRegistration()).stream()
                .filter(f -> f.getStatus() == FlightStatus.SCHEDULED)
                .filter(f -> !f.getScheduledDeparture().isAfter(to) && !f.getScheduledArrival().isBefore(from))
                .forEach(f -> {
                    metrics.recordReplication("conflict");
                    log.warn("REPLICATION CONFLICT: aircraft {} has overlapping flights {} and {} (booked on different "
                                    + "instances while they could not reach each other) - one of them must be cancelled",
                            incoming.getAircraftRegistration(), f.getFlightNumber(), incoming.getFlightNumber());
                });
    }
}
