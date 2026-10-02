package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.clients.AircraftDirectory;
import pt.isep.sidis.flightops.clients.AircraftInfo;
import pt.isep.sidis.flightops.clients.AirportInfo;
import pt.isep.sidis.flightops.clients.RouteDirectory;
import pt.isep.sidis.flightops.clients.RouteInfo;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.domain.FlightStatus;
import pt.isep.sidis.flightops.domain.ScheduledFlight;
import pt.isep.sidis.flightops.peers.PeerClient;
import pt.isep.sidis.flightops.peers.PeerResult;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduledFlightService {

    private static final int TURNAROUND_BUFFER_MINUTES = 30;

    private final ScheduledFlightRepository scheduledFlightRepository;
    private final AircraftDirectory aircraftClient;
    private final RouteDirectory airportsRoutesClient;
    private final FlightQueryService flightQueryService;
    private final PeerClient peers;
    private final AircraftBookingLocks aircraftBookingLocks;

    @Transactional
    public FlightView scheduleFlight(String routeId, String aircraftRegistration,
                                     LocalDateTime departureTime, LocalDateTime arrivalTime) {

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

        LocalDateTime bufferedDeparture = departureTime.minusMinutes(TURNAROUND_BUFFER_MINUTES);
        LocalDateTime bufferedArrival = arrivalTime.plusMinutes(TURNAROUND_BUFFER_MINUTES);

        // Bookings of the same aircraft on this instance run one after the other from here until commit, so the
        // overlap check below always sees a flight that a concurrent booking has just saved (see AircraftBookingLocks).
        aircraftBookingLocks.lock(aircraft.registrationNumber());

        // Local shard
        if (!scheduledFlightRepository.findOverlappingFlightsWithLock(
                aircraft.registrationNumber(), bufferedDeparture, bufferedArrival).isEmpty()) {
            throw new IllegalStateException("The aircraft is already scheduled...");
        }
        // Other shards: best-effort check (no distributed lock -> eventual consistency, see docs).
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
        return FlightView.of(scheduledFlightRepository.save(newFlight));
    }

    public List<FlightView> getScheduledFlightsByAircraft(String aircraftRegistration) {
        aircraftClient.getAircraft(aircraftRegistration); // 404 if the aircraft does not exist
        return flightQueryService.findByAircraft(aircraftRegistration);
    }

    public FlightLookup getFlightById(String flightNumber) {
        return flightQueryService.findById(flightNumber);
    }

    /** Cancels the flight on whichever replica holds it. */
    public FlightView cancelFlight(String flightNumber) {
        FlightView local = cancelLocal(flightNumber);
        if (local != null) {
            return local;
        }
        PeerResult<FlightView> remote = peers.patchOne("/internal/flights/{n}/cancel", FlightView.class, flightNumber);
        if (!remote.items().isEmpty()) {
            return remote.items().get(0);
        }
        throw new ResourceNotFoundException(remote.notFoundMessage("Scheduled flight not found with number: " + flightNumber));
    }

    /** Cancels a flight stored on this replica; returns null if it is not here. */
    @Transactional
    public FlightView cancelLocal(String flightNumber) {
        return scheduledFlightRepository.findById(flightNumber)
                .map(flight -> {
                    flight.cancel();
                    return FlightView.of(scheduledFlightRepository.save(flight));
                })
                .orElse(null);
    }

    public List<FlightView> getUpcomingDepartures(String originIata, int hoursWindow) {
        if (hoursWindow <= 0) {
            throw new IllegalArgumentException("The time window must be greater than zero hours.");
        }
        String iataCode = originIata.toUpperCase();
        airportsRoutesClient.getAirport(iataCode); // 404 if the airport does not exist
        return flightQueryService.findUpcomingDepartures(iataCode, hoursWindow);
    }
}
