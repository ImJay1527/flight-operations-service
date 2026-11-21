package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.common.exceptions.ServiceUnavailableException;
import pt.isep.sidis.flightops.events.FlightChanged;
import pt.isep.sidis.flightops.peers.PeerClient;
import pt.isep.sidis.flightops.peers.PeerResult;
import pt.isep.sidis.flightops.resilience.CircuitOpenException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Function;

/**
 * The write side of CQRS: handles the commands. Reads are not here: they are answered by {@link FlightQueries}.
 *
 * <p>Bookings are sharded by aircraft (P1 p.15) and replicated: the aircraft's flights are kept on the instances
 * {@link Cluster#replicasOf} returns. A booking is made on the first of them that can be reached - the owner, or
 * its backup while the owner is down - so that instance's per-aircraft lock prevents double-booking across
 * instances too. That instance then copies the flight to the others.
 *
 * <p>Every change ends with a {@link FlightChanged} event, which updates this instance's read model. A change made
 * by a peer comes back as its answer, and is published here too, so this instance reads it back at once.
 */
@Service
@RequiredArgsConstructor
public class FlightCommandService {

    private static final Logger log = LoggerFactory.getLogger(FlightCommandService.class);

    private final FlightQueryService flightQueryService;
    private final PeerClient peers;
    private final LocalBookingService localBookings;
    private final Cluster cluster;
    private final ApplicationEventPublisher events;

    /**
     * Tries the aircraft's instances in order. One that certainly did not get the request (connection refused,
     * circuit open) is skipped. One that was reached but did not answer in time leaves the outcome unknown: 503,
     * rather than risking a second booking. If none can be reached, the booking is made here - availability first
     * (AP) - and copied to them when they are back.
     */
    public Booking handle(ScheduleFlightCommand command) {
        String aircraftRegistration = command.aircraftRegistration();
        List<String> replicas = cluster.replicasOf(aircraftRegistration);
        for (String instance : replicas) {
            if (instance.equals(cluster.self())) {
                return new Booking(localBookings.book(command), instance, replicas);
            }
            try {
                FlightView flight = peers.postTo(instance, "/internal/flights", new InternalBookingRequest(
                        command.routeId(), aircraftRegistration, command.departureTime(), command.arrivalTime()),
                        FlightView.class);
                return new Booking(changedElsewhere(flight), instance, replicas);
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
        return new Booking(localBookings.book(command), cluster.self(), replicas);
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

    /**
     * Cancels the flight: here if this instance has a copy, otherwise on a peer that has one. A left-over copy of an
     * aircraft this instance no longer holds is cancelled on the aircraft's holders instead; only if none of them can
     * be reached is it cancelled here (the outbox then sends the change to them).
     */
    public FlightView handle(CancelFlightCommand command) {
        return changeFlight(command.flightNumber(), "cancel", localBookings::cancel);
    }

    /** Marks the flight as completed (landed), routed the same way as a cancellation. */
    public FlightView handle(CompleteFlightCommand command) {
        return changeFlight(command.flightNumber(), "complete", localBookings::complete);
    }

    private FlightView changeFlight(String flightNumber, String action, Function<String, FlightView> locally) {
        String internalPath = "/internal/flights/{n}/" + action;
        FlightView copy = flightQueryService.localById(flightNumber);
        if (copy != null && flightQueryService.isLeftover(copy)) {
            PeerResult<FlightView> onHolders = peers.patchOneOn(cluster.replicasOf(copy.aircraftRegistration()),
                    internalPath, FlightView.class, flightNumber);
            if (!onHolders.items().isEmpty()) {
                return changedElsewhere(onHolders.items().get(0));
            }
        }
        FlightView local = locally.apply(flightNumber);
        if (local != null) {
            return local;
        }
        PeerResult<FlightView> remote = peers.patchOne(internalPath, FlightView.class, flightNumber);
        if (!remote.items().isEmpty()) {
            return changedElsewhere(remote.items().get(0));
        }
        if (remote.mayHideIt(cluster.replicationFactor())) {
            throw new ServiceUnavailableException(remote.unavailableMessage("scheduled flight " + flightNumber));
        }
        throw new ResourceNotFoundException("Scheduled flight not found with number: " + flightNumber);
    }

    /** A change a peer made for this request: this instance's read model learns it now (read your own writes). */
    private FlightView changedElsewhere(FlightView flight) {
        events.publishEvent(FlightChanged.learnt(flight));
        return flight;
    }
}
