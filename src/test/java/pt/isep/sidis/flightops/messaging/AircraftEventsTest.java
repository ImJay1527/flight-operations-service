package pt.isep.sidis.flightops.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.repositories.AircraftCopyRepository;
import pt.isep.sidis.flightops.services.LocalBookingService;
import pt.isep.sidis.flightops.services.ScheduleFlightCommand;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Aircraft &amp; Maintenance service's events, given straight to the listener (the broker is "down": nothing
 * listens on the port), with the JSON of the contract (docs/service-contracts.md "Events"), and what bookings see
 * after them. No Docker needed.
 */
@SpringBootTest(properties = {
        "flightops.messaging.enabled=true",
        "spring.rabbitmq.port=1",
        "flightops.messaging.relay-interval-ms=3600000"
})
@ActiveProfiles("stub")
class AircraftEventsTest {

    @Autowired FlightEventListener listener;
    @Autowired LocalBookingService bookings;
    @Autowired AircraftCopyRepository copies;

    private final Instant t = Instant.parse("2026-11-20T11:30:00Z");

    private static Message message(String routingKey, String body) {
        Message m = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setHeader("eventId", "e-" + body.hashCode()).build();
        m.getMessageProperties().setReceivedRoutingKey(routingKey);
        return m;
    }

    private static Message created(String registration, String status, Instant at) {
        return message("aircraft.created", """
                {"eventId":"a7b2c3d4","eventType":"AircraftCreatedEvent","occurredAt":"%s",
                 "registration":"%s","serialNumber":"SN-1","modelCode":"A320neo","modelName":"Airbus",
                 "maxRange":6300.0,"fuelCapacity":24000.0,"fuelBurnRate":3.8095,"seatingCapacity":180,
                 "status":"%s","flightHours":0.0}""".formatted(at, registration, status));
    }

    private static Message statusChanged(String registration, String from, String to, Instant at) {
        return message("aircraft.status.changed", """
                {"eventId":"3c90f28e","eventType":"AircraftStatusChangedEvent","occurredAt":"%s",
                 "registration":"%s","previousStatus":"%s","newStatus":"%s","totalFlightHours":1500.0}"""
                .formatted(at, registration, from, to));
    }

    private void book(String registration, int daysAhead) {
        LocalDateTime departure = LocalDateTime.now().plusDays(daysAhead).withNano(0);
        bookings.book(new ScheduleFlightCommand("route-opo-lis", registration, departure, departure.plusMinutes(45)));
    }

    @Test
    void anAircraftCreatedThereCanBeBookedHereWithoutAskingThatService() {
        // the built-in data (standing in for the REST API) doesn't know CS-EVA
        assertThatThrownBy(() -> book("CS-EVA", 5000)).isInstanceOf(ResourceNotFoundException.class);

        listener.on(created("CS-EVA", "AVAILABLE", t));

        book("CS-EVA", 5000);
        assertThat(copies.findById("CS-EVA")).get().satisfies(c -> {
            assertThat(c.getModelName()).isEqualTo("A320neo");   // modelCode: what the airports are certified for
            assertThat(c.getActiveCapacity()).isEqualTo(180);
            assertThat(c.getSource()).isEqualTo("event");
        });
    }

    @Test
    void maintenanceStopsBookingsUntilTheAircraftIsBack() {
        listener.on(created("CS-EVB", "AVAILABLE", t));
        listener.on(statusChanged("CS-EVB", "AVAILABLE", "UNDER_MAINTENANCE", t.plusSeconds(60)));
        listener.on(message("aircraft.maintenance.scheduled", """
                {"eventId":"4d5e","eventType":"MaintenanceScheduledEvent","registration":"CS-EVB",
                 "scheduledDate":"2026-11-22","estimatedHours":8.0}"""));

        assertThatThrownBy(() -> book("CS-EVB", 5001)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNDER_MAINTENANCE");

        // the same event again, and an older one arriving late: nothing changes
        listener.on(statusChanged("CS-EVB", "AVAILABLE", "UNDER_MAINTENANCE", t.plusSeconds(60)));
        listener.on(statusChanged("CS-EVB", "IN_FLIGHT", "AVAILABLE", t.plusSeconds(30)));
        assertThat(copies.findById("CS-EVB").get().getStatus()).isEqualTo("UNDER_MAINTENANCE");

        listener.on(statusChanged("CS-EVB", "UNDER_MAINTENANCE", "AVAILABLE", t.plusSeconds(120)));
        book("CS-EVB", 5001);
    }

    @Test
    void anAircraftAskedForBeforeItsEventsIsReadOnceAndThenFollowsThem() {
        book("CS-TPF", 5002);   // not in the copy yet: read from the source and kept
        assertThat(copies.findById("CS-TPF").get().getSource()).isEqualTo("rest");

        listener.on(statusChanged("CS-TPF", "AVAILABLE", "INACTIVE", Instant.now().plusSeconds(1)));

        assertThatThrownBy(() -> book("CS-TPF", 5003)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aStatusChangeOfAnAircraftNotInTheCopyIsLeftForLater() {
        listener.on(statusChanged("CS-EVC", "AVAILABLE", "INACTIVE", t));

        assertThat(copies.findById("CS-EVC")).isEmpty();
    }

    @Test
    void anAircraftEventThatCannotBeUsedIsRejected() {
        assertThatThrownBy(() -> listener.on(message("aircraft.created", "not json")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.on(message("aircraft.created", "{\"registration\":\"CS-EVD\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.on(message("aircraft.status.changed", "{\"registration\":\"CS-EVD\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(copies.findById("CS-EVD")).isEmpty();
    }
}
