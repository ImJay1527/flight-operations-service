package pt.isep.sidis.flightops.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.domain.OutboxMessage;
import pt.isep.sidis.flightops.replication.ReplicaStore;
import pt.isep.sidis.flightops.repositories.OutboxMessageRepository;
import pt.isep.sidis.flightops.services.CancelFlightCommand;
import pt.isep.sidis.flightops.services.FlightCommandService;
import pt.isep.sidis.flightops.services.ScheduleFlightCommand;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transactional outbox of the broker, with the broker down (nothing listens on the port): the changes still
 * work, and each one made here leaves exactly one event waiting, in the same transaction. No Docker needed.
 */
@SpringBootTest(properties = {
        "flightops.messaging.enabled=true",
        "spring.rabbitmq.port=1",                         // nothing there: the broker is "down"
        "flightops.messaging.relay-interval-ms=3600000"   // the relay only runs on wake-ups
})
@ActiveProfiles("stub")
class EventOutboxTest {

    @Autowired FlightCommandService commands;
    @Autowired ReplicaStore replicas;
    @Autowired OutboxMessageRepository outbox;
    @Autowired MessagingStatus status;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private final LocalDateTime departure = LocalDateTime.now().plusDays(4000).withNano(0);

    @BeforeEach
    void emptyOutbox() {
        outbox.deleteAll();
    }

    @Test
    void bookingAndCancelEachLeaveOneEventWhileTheBrokerIsDown() throws Exception {
        FlightView booked = commands.handle(new ScheduleFlightCommand("route-opo-lis", "CS-TPA", departure,
                departure.plusMinutes(45))).flight();
        commands.handle(new CancelFlightCommand(booked.flightNumber()));

        List<OutboxMessage> waiting = outbox.findTop100ByOrderByIdAsc();
        assertThat(waiting).extracting(OutboxMessage::getRoutingKey)
                .containsExactly("flight.scheduled", "flight.cancelled");
        JsonNode cancelled = json.readTree(waiting.get(1).getPayload());
        assertThat(cancelled.get("flightNumber").asText()).isEqualTo(booked.flightNumber());
        assertThat(cancelled.get("revision").asLong()).isEqualTo(2);
        assertThat(status.snapshot().outbox()).isEqualTo(2);
        assertThat(status.snapshot().enabled()).isTrue();
    }

    @Test
    void theEventIsEncryptedAtRestLikeTheFlight() {
        commands.handle(new ScheduleFlightCommand("route-opo-lis", "CS-TPC", departure.plusDays(1),
                departure.plusDays(1).plusMinutes(45)));

        String stored = jdbc.queryForObject("SELECT payload FROM event_outbox", String.class);
        assertThat(stored).startsWith("enc:v1:").doesNotContain("CS-TPC");
    }

    @Test
    void aRefusedBookingLeavesNoEvent() {
        assertThatThrownBy(() -> commands.handle(new ScheduleFlightCommand("route-opo-lis", "CS-TPA",
                departure.plusDays(2), departure.plusDays(2).minusMinutes(5))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(outbox.count()).isZero();
    }

    @Test
    void aCopyFromAnotherInstanceIsNotAnnouncedAgain() {
        // the instance that made the change has already put it on the broker
        replicas.apply(new FlightView("copy-1", "route-opo-lis", "CS-TPB", "737 MAX", "OPO", "LIS", 277.0, 3.4,
                departure.plusDays(3), departure.plusDays(3).plusMinutes(45), "SCHEDULED", 1));

        assertThat(outbox.count()).isZero();
    }
}
