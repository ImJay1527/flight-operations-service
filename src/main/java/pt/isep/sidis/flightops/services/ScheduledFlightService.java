package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
import pt.isep.sidis.flightops.resilience.CircuitOpenException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Bookings are sharded by aircraft (P1 p.15) and replicated: the aircraft's flights are kept on the instances
 * {@link Cluster#replicasOf} returns. A booking is made on the first of them that can be reached - the owner, or
 * its backup while the owner is down - so that instance's per-aircraft lock prevents double-booking across
 * instances too. That instance then copies the flight to the others.
 */
@Service
@RequiredArgsConstructor
public class ScheduledFlightService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledFlightService.class);

    private final AircraftDirectory aircraftClient;
    private final RouteDirectory airportsRoutesClient;
    private final FlightQueryService flightQueryService;
    private final PeerClient peers;
    private final LocalBookingService localBookings;
    private final Cluster cluster;

    /**
     * Tries the aircraft's instances in order. One that certainly did not get the request (connection refused,
     * circuit open) is skipped. One that was reached but did not answer in time leaves the outcome unknown: 503,
     * rather than risking a second booking. If none can be reached, the booking is made here - availability first
     * (AP) - and copied to them when they are back.
     */
    public Booking scheduleFlight(String routeId, String aircraftRegistration,
                                  LocalDateTime departureTime, LocalDateTime arrivalTime) {
        List<String> replicas = cluster.replicasOf(aircraftRegistration);
        for (String instance : replicas) {
            if (instance.equals(cluster.self())) {
                return new Booking(localBookings.book(routeId, aircraftRegistration, departureTime, arrivalTime),
                        instance, replicas);
            }
            try {
                FlightView flight = peers.postTo(instance, "/internal/flights",
                        new InternalBookingRequest(routeId, aircraftRegistration, departureTime, arrivalTime), FlightView.class);
                return new Booking(flight, instance, replicas);
            } catch (HttpClientErrorException refused) {
                throw ownersAnswer(refused);
            } catch (HttpServerErrorException failed) {
                throw new ServiceUnavailableException(instance + ": " + errorMessage(failed.getResponseBodyAsString()));
            } catch (CircuitOpenException | ResourceAccessException e) {
                if (e instanceof ResourceAccessException && !neverDelivered(e)) {
                    throw new ServiceUnavailableException("The instance holding aircraft " + aircraftRegistration + " ("
                            + instance + ") did not answer in time; the booking may or may not have been made. Check the "
                            + "aircraft's flights before trying again.", e);
                }
                log.warn("{} (holds aircraft {}) unreachable ({}): trying the next instance that holds it",
                        instance, aircraftRegistration, e.getMessage());
            }
        }
        log.warn("No instance holding aircraft {} reachable ({}): booking it here (overlap check best effort)",
                aircraftRegistration, replicas);
        return new Booking(localBookings.book(routeId, aircraftRegistration, departureTime, arrivalTime),
                cluster.self(), replicas);
    }

    /** The owner's 4xx as the same error here, so the client gets the owner's status and message. */
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

    /** True if the request certainly never reached the other instance, so booking elsewhere can't duplicate it. */
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

    /** Cancels the flight: here if this instance has a copy, otherwise on a peer that has one. */
    public FlightView cancelFlight(String flightNumber) {
        FlightView local = localBookings.cancel(flightNumber);
        if (local != null) {
            return local;
        }
        PeerResult<FlightView> remote = peers.patchOne("/internal/flights/{n}/cancel", FlightView.class, flightNumber);
        if (!remote.items().isEmpty()) {
            return remote.items().get(0);
        }
        throw new ResourceNotFoundException(remote.notFoundMessage("Scheduled flight not found with number: " + flightNumber));
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
