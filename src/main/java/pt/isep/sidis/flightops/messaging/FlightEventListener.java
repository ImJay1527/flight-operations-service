package pt.isep.sidis.flightops.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.clients.LocalAircraftCopy;
import pt.isep.sidis.flightops.clients.LocalRouteCopy;
import pt.isep.sidis.flightops.common.metrics.FlightOpsMetrics;
import pt.isep.sidis.flightops.readmodel.ReadModel;

import java.io.IOException;
import java.util.function.Predicate;

/**
 * Consumes this instance's queue, by routing key:
 * <ul>
 *   <li>{@code flight.*}: the flight changes made on the other instances (pushed by the broker within milliseconds)
 *       go into this instance's read model;</li>
 *   <li>{@code aircraft.*}: the Aircraft &amp; Maintenance service's events. aircraft.created and
 *       aircraft.status.changed update this instance's copy of the aircraft ({@link LocalAircraftCopy}), which the
 *       bookings use. The maintenance events are only counted: that service sends an aircraft.status.changed with
 *       each of them;</li>
 *   <li>{@code airport.*}, {@code route.*}: the Airports &amp; Routes service's events update this instance's copy
 *       of the airports and routes ({@link LocalRouteCopy}). A route.created without the route's requirements
 *       (minimum range and seats) is only counted: the route is read from the REST API when needed.</li>
 * </ul>
 * A message is acknowledged after it has been processed (Spring AMQP's AUTO mode: when this method returns).
 * Processing is idempotent - the newest revision wins in the read model, the newest change in the copies - so a
 * duplicate or an event that arrives late changes nothing.
 *
 * <p>Failures: a message that cannot be read is rejected and goes to the dead-letter queue (after the retries,
 * {@code spring.rabbitmq.listener.simple.retry.*}). A read database that is down doesn't reject the message: the
 * read model turns STALE and {@code ReadModelSync} rebuilds it from the write side, which is the source of truth.
 */
@Component
@ConditionalOnProperty(name = "flightops.messaging.enabled", havingValue = "true")
public class FlightEventListener {

    private static final Logger log = LoggerFactory.getLogger(FlightEventListener.class);

    private final ReadModel readModel;
    private final LocalAircraftCopy aircraft;
    private final LocalRouteCopy routes;
    private final ObjectMapper json;
    private final FlightOpsMetrics metrics;
    private final String instanceName;

    public FlightEventListener(ReadModel readModel, LocalAircraftCopy aircraft, LocalRouteCopy routes,
                               ObjectMapper json, FlightOpsMetrics metrics,
                               @Value("${flightops.instance-name}") String instanceName) {
        this.readModel = readModel;
        this.aircraft = aircraft;
        this.routes = routes;
        this.json = json;
        this.metrics = metrics;
        this.instanceName = instanceName;
    }

    @RabbitListener(id = "flight-events", queues = "#{flightEventsQueue.name}")
    public void on(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        if (routingKey != null && routingKey.startsWith("aircraft.")) {
            onAircraftEvent(message, routingKey);
            return;
        }
        if (routingKey != null && (routingKey.startsWith("airport.") || routingKey.startsWith("route."))) {
            onAirportsRoutesEvent(message, routingKey);
            return;
        }
        if (instanceName.equals(message.getMessageProperties().getHeader(EventRelay.INSTANCE_HEADER))) {
            // made here: already in the read model (projected after its commit)
            metrics.recordMessage("ignored", routingKey);
            return;
        }
        FlightEvent event = read(message, routingKey);
        readModel.project(event.flight());
        metrics.recordMessage("received", routingKey);
        log.debug("Event {} ({}): flight {} revision {} projected", event.eventId(), routingKey,
                event.flightNumber(), event.revision());
    }

    private void onAircraftEvent(Message message, String routingKey) {
        boolean changed = switch (routingKey) {
            case AircraftEvent.CREATED -> {
                AircraftEvent event = readAircraft(message, routingKey);
                if (!event.isCompleteAircraft()) {
                    throw rejected(message, routingKey, "aircraft.created without the fields a booking needs");
                }
                yield aircraft.created(event.aircraft(), event.at());
            }
            case AircraftEvent.STATUS_CHANGED -> {
                AircraftEvent event = readAircraft(message, routingKey);
                if (!event.isCompleteStatusChange()) {
                    throw rejected(message, routingKey, "aircraft.status.changed without registration or newStatus");
                }
                yield aircraft.statusChanged(event.registration(), event.newStatus(), event.at());
            }
            default -> false;   // aircraft.maintenance.*: comes with an aircraft.status.changed
        };
        metrics.recordMessage(changed ? "received" : "ignored", routingKey);
        log.debug("Event {} ({}): {}", message.getMessageProperties().getHeader("eventId"), routingKey,
                changed ? "aircraft copy updated" : "nothing to change");
    }

    private void onAirportsRoutesEvent(Message message, String routingKey) {
        boolean changed;
        if (AirportsRoutesEvent.AIRPORT_CREATED.equals(routingKey)) {
            AirportsRoutesEvent e = readAirportsRoutes(message, routingKey, AirportsRoutesEvent::isCompleteAirport);
            changed = routes.airportCreated(e.airport(), e.at());
        } else if (AirportsRoutesEvent.AIRPORT_STATUS.contains(routingKey)) {
            AirportsRoutesEvent e = readAirportsRoutes(message, routingKey,
                    AirportsRoutesEvent::isCompleteStatusChange);
            changed = routes.airportStatusChanged(e.iataCode(), e.newStatus(), e.at());
        } else if (AirportsRoutesEvent.AIRPORT_CERTIFIED.equals(routingKey)) {
            AirportsRoutesEvent e = readAirportsRoutes(message, routingKey,
                    AirportsRoutesEvent::isCompleteCertification);
            changed = routes.airportCertified(e.iataCode(), e.certifiedModels(), e.modelName(), e.at());
        } else if (AirportsRoutesEvent.ROUTE_CREATED.contains(routingKey)) {
            AirportsRoutesEvent e = readAirportsRoutes(message, routingKey, AirportsRoutesEvent::hasRoute);
            changed = e.hasRouteRequirements() && routes.routeCreated(e.route(), e.at());
        } else if (AirportsRoutesEvent.ROUTE_DEACTIVATED.equals(routingKey)) {
            AirportsRoutesEvent e = readAirportsRoutes(message, routingKey, AirportsRoutesEvent::hasRoute);
            changed = routes.routeDeactivated(e.routeId(), e.at());
        } else {
            changed = false;
        }
        metrics.recordMessage(changed ? "received" : "ignored", routingKey);
        log.debug("Event {} ({}): {}", message.getMessageProperties().getHeader("eventId"), routingKey,
                changed ? "route/airport copy updated" : "nothing to change");
    }

    private AirportsRoutesEvent readAirportsRoutes(Message message, String routingKey,
                                                   Predicate<AirportsRoutesEvent> complete) {
        AirportsRoutesEvent event;
        try {
            event = json.readValue(message.getBody(), AirportsRoutesEvent.class);
        } catch (IOException e) {
            throw rejected(message, routingKey, "Not an airport/route event: " + e.getMessage());
        }
        if (!complete.test(event)) {
            throw rejected(message, routingKey, routingKey + " without the fields this service needs");
        }
        return event;
    }

    private AircraftEvent readAircraft(Message message, String routingKey) {
        try {
            return json.readValue(message.getBody(), AircraftEvent.class);
        } catch (IOException e) {
            throw rejected(message, routingKey, "Not an aircraft event: " + e.getMessage());
        }
    }

    private AmqpRejectAndDontRequeueException rejected(Message message, String routingKey, String why) {
        metrics.recordMessage("failed", routingKey);
        return new AmqpRejectAndDontRequeueException(why + " (" + message.getMessageProperties().getHeader("eventId")
                + ")");
    }

    private FlightEvent read(Message message, String routingKey) {
        FlightEvent event;
        try {
            event = json.readValue(message.getBody(), FlightEvent.class);
        } catch (IOException e) {
            metrics.recordMessage("failed", routingKey);
            throw new AmqpRejectAndDontRequeueException("Not a flight event: " + e.getMessage(), e);
        }
        if (!event.isComplete()) {
            metrics.recordMessage("failed", routingKey);
            throw new AmqpRejectAndDontRequeueException("Flight event without the fields the read model needs: "
                    + message.getMessageProperties().getMessageId());
        }
        return event;
    }
}
