package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.clients.AircraftDirectory;
import pt.isep.sidis.flightops.clients.AircraftInfo;
import pt.isep.sidis.flightops.clients.AirportInfo;
import pt.isep.sidis.flightops.clients.RouteDirectory;
import pt.isep.sidis.flightops.clients.RouteInfo;
import pt.isep.sidis.flightops.events.FlightChanged;
import pt.isep.sidis.flightops.domain.FlightStatus;
import pt.isep.sidis.flightops.domain.ScheduledFlight;
import pt.isep.sidis.flightops.replication.ReplicationOutbox;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;

import java.time.LocalDateTime;

/**
 * Changes made on this instance. Booking: the scheduling rules, the per-aircraft lock and the overlap checks; used for
 * aircraft this instance holds (directly, or via POST /internal/flights from a peer) and as the fallback when no
 * instance holding the aircraft can be reached. Every change is queued for the flight's other copies in the same
 * transaction.
 */
@Service
@RequiredArgsConstructor
public class LocalBookingService {

    private final ScheduledFlightRepository scheduledFlightRepository;
    private final AircraftDirectory aircraftClient;
    private final RouteDirectory airportsRoutesClient;
    private final FlightQueryService flightQueryService;
    private final AircraftBookingLocks aircraftBookingLocks;
    private final ReplicationOutbox replicationOutbox;
    private final ApplicationEventPublisher events;

    @Transactional
    public FlightView book(ScheduleFlightCommand command) {
        String routeId = command.routeId();
        String aircraftRegistration = command.aircraftRegistration();
        LocalDateTime departureTime = command.departureTime();
        LocalDateTime arrivalTime = command.arrivalTime();

        if (!arrivalTime.isAfter(departureTime)) {
            throw new IllegalArgumentException("Arrival time must be after departure time.");
        }

        RouteInfo route = airportsRoutesClient.getRoute(routeId);
        AircraftInfo aircraft = aircraftClient.getAircraft(aircraftRegistration);

        if (!route.isActive()) {
            throw new IllegalStateException("Cannot schedule a flight on a deactivated route.");
        }

        AirportInfo origin = route.origin();
        AirportInfo destination = route.destination();

        if (!origin.isOperational() || !destination.isOperational()) {
            throw new IllegalStateException("Both origin and destination airports must be operational.");
        }

        String modelName = aircraft.modelName();

        if (!origin.isCertifiedFor(modelName)) {
            throw new IllegalStateException(
                    "The origin airport (" + origin.iataCode() + ") is not certified for aircraft model: " + modelName);
        }
        if (!destination.isCertifiedFor(modelName)) {
            throw new IllegalStateException(
                    "The destination airport (" + destination.iataCode() + ") is not certified for aircraft model: " + modelName);
        }

        if (aircraft.maxRange() < route.distanceKm()) {
            throw new IllegalArgumentException("Aircraft maximum range is insufficient for this route.");
        }
        if (aircraft.activeCapacity() < route.minCapacityRequired()) {
            throw new IllegalArgumentException("Aircraft active capacity is insufficient for this route's requirements.");
        }
        if (!aircraft.isAvailable()) {
            throw new IllegalStateException("Aircraft is not available for scheduling. Current status: " + aircraft.status());
        }

        LocalDateTime bufferedDeparture = departureTime.minusMinutes(ScheduledFlight.TURNAROUND_BUFFER_MINUTES);
        LocalDateTime bufferedArrival = arrivalTime.plusMinutes(ScheduledFlight.TURNAROUND_BUFFER_MINUTES);

        // From here until commit, bookings of the same aircraft run one after the other, so the overlap check below
        // sees a flight that a concurrent booking has just saved.
        aircraftBookingLocks.lock(aircraft.registrationNumber());

        if (!scheduledFlightRepository.findOverlappingFlightsWithLock(
                aircraft.registrationNumber(), bufferedDeparture, bufferedArrival).isEmpty()) {
            throw new IllegalStateException("The aircraft is already scheduled...");
        }
        // Peers can still hold flights of this aircraft (booked while its owner was down): best-effort check
        boolean overlapsOnPeer = flightQueryService.activeOnPeers(aircraft.registrationNumber()).stream()
                .anyMatch(f -> FlightStatus.SCHEDULED.name().equals(f.status())
                        && !f.scheduledDeparture().isAfter(bufferedArrival)
                        && !f.scheduledArrival().isBefore(bufferedDeparture));
        if (overlapsOnPeer) {
            throw new IllegalStateException("The aircraft is already scheduled...");
        }

        ScheduledFlight newFlight = new ScheduledFlight(
                route.routeId(), aircraft.registrationNumber(), modelName,
                origin.iataCode(), destination.iataCode(), route.distanceKm(), aircraft.fuelBurnRate(),
                departureTime, arrivalTime);
        return changed(scheduledFlightRepository.save(newFlight));
    }

    /** Cancels this instance's copy of a flight; null if it is not here. */
    @Transactional
    public FlightView cancel(String flightNumber) {
        return scheduledFlightRepository.findById(flightNumber)
                .map(flight -> {
                    flight.cancel();
                    return changed(scheduledFlightRepository.save(flight));
                })
                .orElse(null);
    }

    /** Marks this instance's copy of a flight as completed (landed); null if it is not here. */
    @Transactional
    public FlightView complete(String flightNumber) {
        return scheduledFlightRepository.findById(flightNumber)
                .map(flight -> {
                    flight.complete(LocalDateTime.now());
                    return changed(scheduledFlightRepository.save(flight));
                })
                .orElse(null);
    }

    /**
     * In the change's transaction: queues it for the flight's other copies (outbox) and publishes it for the read
     * side (projected once the transaction has committed).
     */
    private FlightView changed(ScheduledFlight saved) {
        replicationOutbox.flightChanged(saved);
        FlightView view = FlightView.of(saved);
        events.publishEvent(FlightChanged.madeHere(view));
        return view;
    }
}
