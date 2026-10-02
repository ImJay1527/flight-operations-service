package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.clients.AircraftDirectory;
import pt.isep.sidis.flightops.clients.RouteDirectory;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.common.exceptions.ServiceUnavailableException;
import pt.isep.sidis.flightops.peers.PeerClient;
import pt.isep.sidis.flightops.peers.PeerResult;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;
import pt.isep.sidis.flightops.resilience.CircuitOpenException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled flights across the instances. Bookings are sharded by aircraft registration (P1 p.15): a booking is made
 * on the instance that owns the aircraft ({@link Cluster#ownerOf}), forwarding it there if needed, so all flights of
 * an aircraft are on one instance and its per-aircraft lock prevents double-booking across instances too.
 */
@Service
@RequiredArgsConstructor
public class ScheduledFlightService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledFlightService.class);

    private final ScheduledFlightRepository scheduledFlightRepository;
    private final AircraftDirectory aircraftClient;
    private final RouteDirectory airportsRoutesClient;
    private final FlightQueryService flightQueryService;
    private final PeerClient peers;
    private final LocalBookingService localBookings;
    private final Cluster cluster;

    /**
     * Books on the owner of the aircraft. If the owner can't be reached and the request certainly did not get there
     * (connection refused, circuit open), the booking is made here instead - availability first (AP), with the
     * best-effort overlap check. If the owner was reached but did not answer in time, the outcome is unknown: 503,
     * rather than risking a second booking.
     */
    public Booking scheduleFlight(String routeId, String aircraftRegistration,
                                  LocalDateTime departureTime, LocalDateTime arrivalTime) {
        String owner = cluster.ownerOf(aircraftRegistration);
        if (owner.equals(cluster.self())) {
            return new Booking(localBookings.book(routeId, aircraftRegistration, departureTime, arrivalTime), owner);
        }
        try {
            FlightView flight = peers.postTo(owner, "/internal/flights",
                    new InternalBookingRequest(routeId, aircraftRegistration, departureTime, arrivalTime), FlightView.class);
            return new Booking(flight, owner);
        } catch (HttpClientErrorException refused) {
            throw ownersAnswer(refused);                  // e.g. 409 "already scheduled", 404 unknown aircraft
        } catch (HttpServerErrorException failed) {
            throw new ServiceUnavailableException(owner + ": " + errorMessage(failed.getResponseBodyAsString()));
        } catch (CircuitOpenException | ResourceAccessException e) {
            if (e instanceof ResourceAccessException && !neverDelivered(e)) {
                throw new ServiceUnavailableException("The instance that owns aircraft " + aircraftRegistration + " (" + owner
                        + ") did not answer in time; the booking may or may not have been made. Check the aircraft's "
                        + "flights before trying again.", e);
            }
            log.warn("Owner {} of aircraft {} unreachable ({}): booking it here instead (overlap check best effort)",
                    owner, aircraftRegistration, e.getMessage());
            return new Booking(localBookings.book(routeId, aircraftRegistration, departureTime, arrivalTime), cluster.self());
        }
    }

    /** The owner's 4xx, as the same kind of error here (so the client gets the owner's status and message). */
    private RuntimeException ownersAnswer(HttpClientErrorException e) {
        String message = errorMessage(e.getResponseBodyAsString());
        return switch (e.getStatusCode().value()) {
            case 404 -> new ResourceNotFoundException(message);
            case 409 -> new IllegalStateException(message);
            case 400 -> new IllegalArgumentException(message);
            default -> new ServiceUnavailableException("The owning instance refused the booking (" + e.getStatusCode() + "): " + message);
        };
    }

    private static String errorMessage(String body) {
        int i = body == null ? -1 : body.indexOf("\"error\":\"");
        if (i < 0) {
            return body == null || body.isBlank() ? "no details" : body;
        }
        int start = i + "\"error\":\"".length();
        int end = body.indexOf('"', start);
        return end < 0 ? body.substring(start) : body.substring(start, end);
    }

    /** True if the request certainly never reached the other instance (so trying elsewhere can't duplicate it). */
    private static boolean neverDelivered(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof ConnectTimeoutException
                    || t instanceof UnknownHostException || t instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
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
