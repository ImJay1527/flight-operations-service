package pt.isep.sidis.flightops.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import pt.isep.sidis.flightops.domain.OutboxMessage;
import pt.isep.sidis.flightops.events.FlightChanged;
import pt.isep.sidis.flightops.repositories.OutboxMessageRepository;

import java.time.Instant;

/**
 * Transactional outbox for the broker: a change this instance made ({@link FlightChanged#madeHere()}) is saved as
 * an {@link OutboxMessage} just before its transaction commits, in that transaction. {@link EventRelay} sends it
 * right after the commit. Changes learnt from other instances are not announced again: whoever made them did.
 */
@Component
@ConditionalOnProperty(name = "flightops.messaging.enabled", havingValue = "true")
public class EventOutbox {

    private final OutboxMessageRepository outbox;
    private final EventRelay relay;
    private final ObjectMapper json;

    public EventOutbox(OutboxMessageRepository outbox, EventRelay relay, ObjectMapper json) {
        this.outbox = outbox;
        this.relay = relay;
        this.json = json;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(FlightChanged change) {
        if (!change.madeHere()) {
            return;
        }
        FlightEvent event = FlightEvent.of(change.flight(), Instant.now());
        try {
            outbox.save(new OutboxMessage(event.eventId(), event.routingKey(), event.eventType(),
                    json.writeValueAsString(event)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Flight event could not be written as JSON", e);
        }
    }

    /** Committed: send it now rather than at the relay's next round. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(FlightChanged change) {
        if (change.madeHere()) {
            relay.wakeUp();
        }
    }
}
