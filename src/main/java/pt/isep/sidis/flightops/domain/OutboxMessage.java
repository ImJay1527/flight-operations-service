package pt.isep.sidis.flightops.domain;

import jakarta.persistence.*;
import lombok.Getter;
import pt.isep.sidis.flightops.common.crypto.EncryptedString;

import java.time.Instant;

/**
 * An event waiting to go to the message broker (transactional outbox). Saved in the same transaction as the change
 * it announces, so a change is never stored without its event, and an event never leaves for a change that was
 * rolled back. Deleted once the broker has confirmed it. The payload holds the flight's route, aircraft and
 * airports, so it is encrypted at rest like the flight itself.
 */
@Entity
@Getter
@Table(name = "event_outbox")
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String eventId;

    @Column(nullable = false, length = 64)
    private String routingKey;

    @Column(nullable = false, length = 64)
    private String eventType;

    /** The event as JSON. */
    @Column(nullable = false, length = 8000)
    @Convert(converter = EncryptedString.class)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    protected OutboxMessage() {
    }

    public OutboxMessage(String eventId, String routingKey, String eventType, String payload) {
        this.eventId = eventId;
        this.routingKey = routingKey;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }
}
