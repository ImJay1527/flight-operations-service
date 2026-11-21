package pt.isep.sidis.flightops.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.repositories.RouteCopyRepository;
import pt.isep.sidis.flightops.services.LocalBookingService;
import pt.isep.sidis.flightops.services.ScheduleFlightCommand;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Airports &amp; Routes service's events, given straight to the listener (the broker is "down"), in the JSON
 * that service publishes ({@code @type}, {@code sourceInstance}, {@code eventType} AIRPORT_CREATED, ...), and what
 * bookings see after them. Airports FNC and PDL and their route exist only through the events (the built-in data
 * doesn't know them). No Docker needed.
 */
@SpringBootTest(properties = {
        "flightops.messaging.enabled=true",
        "spring.rabbitmq.port=1",
        "flightops.messaging.relay-interval-ms=3600000"
})
@ActiveProfiles("stub")
class RouteEventsTest {

    @Autowired FlightEventListener listener;
    @Autowired LocalBookingService bookings;
    @Autowired RouteCopyRepository routes;

    private final Instant t = Instant.parse("2026-11-21T10:00:00Z");

    private static Message message(String routingKey, String body) {
        Message m = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").build();
        m.getMessageProperties().setReceivedRoutingKey(routingKey);
        return m;
    }

    private static Message airportCreated(String iata, String models, Instant at) {
        return message("airport.created", """
                {"@type":"pt.isep.sidis.airports.events.AirportCreatedEvent","eventId":"a1","eventType":"AIRPORT_CREATED",
                 "occurredAt":"%s","sourceInstance":"instance1","iataCode":"%s","name":"Airport %s","city":"X",
                 "country":"Portugal","status":"OPERATIONAL","certifiedModels":[%s]}""".formatted(at, iata, iata, models));
    }

    private static Message airportStatus(String iata, String status, Instant at) {
        return message("airport.status", """
                {"@type":"pt.isep.sidis.airports.events.AirportStatusUpdatedEvent","eventId":"a2",
                 "eventType":"AIRPORT_STATUS_UPDATED","occurredAt":"%s","sourceInstance":"instance1",
                 "iataCode":"%s","newStatus":"%s"}""".formatted(at, iata, status));
    }

    private static Message routeCreated(String id, String requirements, Instant at) {
        return message("route.created", """
                {"@type":"pt.isep.sidis.airports.events.FlightRouteCreatedEvent","eventId":"r1",
                 "eventType":"FLIGHT_ROUTE_CREATED","occurredAt":"%s","sourceInstance":"instance1","routeId":"%s",
                 "originIata":"FNC","destinationIata":"PDL","distanceKm":1000.0,"estimatedFlightTime":120,
                 "status":"ACTIVE"%s}""".formatted(at, id, requirements));
    }

    private void book(String routeId, String registration, int daysAhead) {
        LocalDateTime departure = LocalDateTime.now().plusDays(daysAhead).withNano(0);
        bookings.book(new ScheduleFlightCommand(routeId, registration, departure, departure.plusMinutes(120)));
    }

    /** FNC (A320neo only) and PDL (both models), and route-fnc-pdl with its requirements. */
    private void islands(String routeId) {
        listener.on(airportCreated("FNC", "\"A320neo\"", t));
        listener.on(airportCreated("PDL", "\"A320neo\",\"737 MAX\"", t));
        listener.on(routeCreated(routeId, ",\"minRangeRequired\":1200.0,\"minCapacityRequired\":120", t));
    }

    @Test
    void aRouteCreatedThereCanBeBookedHereWithoutAskingThatService() {
        assertThatThrownBy(() -> book("route-fnc-pdl", "CS-TPA", 6000)).isInstanceOf(ResourceNotFoundException.class);

        islands("route-fnc-pdl");

        book("route-fnc-pdl", "CS-TPA", 6000);
        assertThat(routes.findById("route-fnc-pdl").get().getMinCapacityRequired()).isEqualTo(120);
    }

    @Test
    void aClosedAirportStopsBookingsUntilItOpensAgain() {
        islands("route-fnc-pdl-2");
        listener.on(airportStatus("PDL", "CLOSED", t.plusSeconds(60)));

        assertThatThrownBy(() -> book("route-fnc-pdl-2", "CS-TPC", 6001)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operational");

        listener.on(airportStatus("PDL", "OPERATIONAL", t.plusSeconds(30)));   // older, arrives late: ignored
        assertThatThrownBy(() -> book("route-fnc-pdl-2", "CS-TPC", 6001)).isInstanceOf(IllegalStateException.class);

        listener.on(airportStatus("PDL", "OPERATIONAL", t.plusSeconds(120)));
        book("route-fnc-pdl-2", "CS-TPC", 6001);
    }

    @Test
    void aCertificationAddedThereLetsANewModelFlyHere() {
        islands("route-fnc-pdl-3");
        assertThatThrownBy(() -> book("route-fnc-pdl-3", "CS-TPB", 6002))   // 737 MAX: FNC not certified
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not certified");

        listener.on(message("airport.certification.added", """
                {"eventId":"c1","eventType":"AirportCertificationAddedEvent","occurredAt":"%s","iataCode":"FNC",
                 "certifiedModels":["A320neo","737 MAX"]}""".formatted(t.plusSeconds(60))));

        book("route-fnc-pdl-3", "CS-TPB", 6002);
    }

    @Test
    void aDeactivatedRouteCantBeBooked() {
        islands("route-fnc-pdl-4");
        listener.on(message("route.deactivated", """
                {"@type":"pt.isep.sidis.airports.events.FlightRouteDeactivatedEvent","eventId":"r2",
                 "eventType":"FLIGHT_ROUTE_DEACTIVATED","occurredAt":"%s","routeId":"route-fnc-pdl-4",
                 "reason":"Deactivated by operator"}""".formatted(t.plusSeconds(60))));

        assertThatThrownBy(() -> book("route-fnc-pdl-4", "CS-TPA", 6003)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    void aRouteCreatedWithoutItsRequirementsIsLeftToTheRestApi() {
        // what the service publishes today: no minRangeRequired / minCapacityRequired
        listener.on(routeCreated("route-fnc-pdl-5", "", t));

        assertThat(routes.findById("route-fnc-pdl-5")).isEmpty();
    }

    @Test
    void anAirportOrRouteEventThatCannotBeUsedIsRejected() {
        assertThatThrownBy(() -> listener.on(message("airport.created", "not json")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.on(message("airport.status", "{\"iataCode\":\"FNC\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.on(message("route.deactivated", "{\"reason\":\"x\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }
}
