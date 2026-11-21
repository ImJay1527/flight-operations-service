package pt.isep.sidis.flightops.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import pt.isep.sidis.flightops.api.dto.FlightView;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The flight events as other services see them on the broker (docs/service-contracts.md "Events"). */
class FlightEventTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final LocalDateTime dep = LocalDateTime.of(2026, 11, 20, 10, 0);

    private FlightView flight(String status, long revision) {
        return new FlightView("F-1", "route-opo-lis", "CS-TPA", "A320neo", "OPO", "LIS", 277.0, 3.8,
                dep, dep.plusMinutes(51), status, revision);
    }

    @Test
    void completedFlightHasTheFieldsAircraftAndMaintenanceReads() throws Exception {
        JsonNode event = json.readTree(json.writeValueAsString(FlightEvent.of(flight("COMPLETED", 2), Instant.now())));

        // FlightCompletedEvent of aircraft-maintenance-service (docs/AMQP_CONTRACT.md 3.1)
        assertThat(event.get("eventType").asText()).isEqualTo("FlightCompletedEvent");
        assertThat(event.get("eventId").asText()).hasSize(36);
        assertThat(event.get("occurredAt").asText()).endsWith("Z");
        assertThat(event.get("flightNumber").asText()).isEqualTo("F-1");
        assertThat(event.get("registration").asText()).isEqualTo("CS-TPA");
        assertThat(event.get("originAirportIata").asText()).isEqualTo("OPO");
        assertThat(event.get("destinationAirportIata").asText()).isEqualTo("LIS");
        assertThat(event.get("durationHours").asDouble()).isEqualTo(0.85);
        assertThat(FlightEvent.of(flight("COMPLETED", 2), Instant.now()).routingKey()).isEqualTo("flight.completed");
    }

    @Test
    void onlyACompletedFlightCarriesFlightHours() throws Exception {
        // a consumer that adds flight hours must never add them for a booking or a cancellation
        JsonNode scheduled = json.readTree(json.writeValueAsString(FlightEvent.of(flight("SCHEDULED", 1), Instant.now())));
        JsonNode cancelled = json.readTree(json.writeValueAsString(FlightEvent.of(flight("CANCELED", 2), Instant.now())));

        assertThat(scheduled.has("durationHours")).isFalse();
        assertThat(cancelled.has("durationHours")).isFalse();
        assertThat(scheduled.get("eventType").asText()).isEqualTo("FlightScheduledEvent");
        assertThat(cancelled.get("eventType").asText()).isEqualTo("FlightCancelledEvent");
        assertThat(FlightEvent.of(flight("CANCELED", 2), Instant.now()).routingKey()).isEqualTo("flight.cancelled");
    }

    @Test
    void theSameChangeAlwaysHasTheSameEventId() {
        FlightEvent first = FlightEvent.of(flight("CANCELED", 2), Instant.now());
        FlightEvent again = FlightEvent.of(flight("CANCELED", 2), Instant.now().plusSeconds(5));
        FlightEvent other = FlightEvent.of(flight("SCHEDULED", 1), Instant.now());

        assertThat(again.eventId()).isEqualTo(first.eventId());     // a consumer can drop the duplicate
        assertThat(other.eventId()).isNotEqualTo(first.eventId());
    }

    @Test
    void anEventGivesBackTheWholeFlightForTheReadModel() throws Exception {
        FlightView original = flight("SCHEDULED", 1);
        FlightEvent received = json.readValue(json.writeValueAsString(FlightEvent.of(original, Instant.now())),
                FlightEvent.class);

        assertThat(received.isComplete()).isTrue();
        assertThat(received.flight()).isEqualTo(original);
    }

    @Test
    void anEventWithoutTheFlightIsNotComplete() throws Exception {
        FlightEvent foreign = json.readValue("{\"eventId\":\"x\",\"flightNumber\":\"F-1\",\"registration\":\"CS-TPA\","
                + "\"durationHours\":1.0,\"unknownField\":true}", FlightEvent.class);

        assertThat(foreign.isComplete()).isFalse();
    }
}
