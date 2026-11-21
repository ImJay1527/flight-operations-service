package pt.isep.sidis.flightops.messaging;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.common.metrics.FlightOpsMetrics;
import pt.isep.sidis.flightops.domain.OutboxMessage;
import pt.isep.sidis.flightops.repositories.OutboxMessageRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Delivers the outbox ({@link EventOutbox}) to the broker: sends the waiting events in order, waits until the broker
 * confirms it has them (publisher confirms: persistent messages, written to disk), and only then deletes them.
 * Runs right after a commit and every {@code relay-interval-ms}, so events made while the broker is down go out
 * when it is back. A crash between "confirmed" and "deleted" sends an event twice: delivery is at least once, and
 * the event id lets consumers drop the duplicate. Its own thread: a slow broker never delays a request.
 */
@Component
@ConditionalOnProperty(name = "flightops.messaging.enabled", havingValue = "true")
public class EventRelay {

    public static final String APP_ID = "flight-operations-service";
    /** Header with the name of the instance that made the change (its own events come back to it). */
    public static final String INSTANCE_HEADER = "instance";

    private static final Logger log = LoggerFactory.getLogger(EventRelay.class);

    private final OutboxMessageRepository outbox;
    private final RabbitTemplate rabbit;
    private final MessagingConfig.Settings settings;
    private final FlightOpsMetrics metrics;
    private final String instanceName;
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "event-relay"));
    private final AtomicBoolean wakeUpQueued = new AtomicBoolean();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public EventRelay(OutboxMessageRepository outbox, RabbitTemplate rabbit, MessagingConfig.Settings settings,
                      FlightOpsMetrics metrics, @Value("${flightops.instance-name}") String instanceName) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.settings = settings;
        this.metrics = metrics;
        this.instanceName = instanceName;
        thread.scheduleWithFixedDelay(this::sendPendingSafely, settings.relayIntervalMs(), settings.relayIntervalMs(),
                TimeUnit.MILLISECONDS);
    }

    /** Sends as soon as possible (after a commit). Several calls in a row cause a single run. */
    public void wakeUp() {
        if (wakeUpQueued.compareAndSet(false, true)) {
            thread.execute(() -> {
                wakeUpQueued.set(false);
                sendPendingSafely();
            });
        }
    }

    /** Why the last run failed (broker down...), or null if it worked. */
    public String lastError() {
        return lastError.get();
    }

    private void sendPendingSafely() {
        try {
            sendPending();
        } catch (RuntimeException e) {
            log.warn("Event relay run failed, will retry: {}", e.getMessage());
        }
    }

    void sendPending() {
        List<OutboxMessage> batch = outbox.findTop100ByOrderByIdAsc();
        if (batch.isEmpty()) {
            lastError.set(null);
            return;
        }
        try {
            rabbit.invoke(channel -> {
                for (OutboxMessage m : batch) {
                    channel.send(settings.exchange(), m.getRoutingKey(), message(m));
                }
                channel.waitForConfirmsOrDie(settings.confirmTimeoutMs());
                return null;
            });
        } catch (AmqpException e) {
            lastError.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.debug("Broker unavailable, {} event(s) stay in the outbox: {}", batch.size(), e.getMessage());
            return;
        }
        outbox.deleteAllByIdInBatch(batch.stream().map(OutboxMessage::getId).toList());
        lastError.set(null);
        batch.forEach(m -> metrics.recordMessage("published", m.getRoutingKey()));
        log.debug("{} event(s) published to {}", batch.size(), settings.exchange());
        if (batch.size() == 100) {
            wakeUp();   // more waiting
        }
    }

    private Message message(OutboxMessage m) {
        return MessageBuilder.withBody(m.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(m.getEventId())
                .setType(m.getEventType())
                .setAppId(APP_ID)
                .setTimestamp(Date.from(m.getCreatedAt()))
                .setHeader("eventId", m.getEventId())
                .setHeader("eventType", m.getEventType())
                .setHeader(INSTANCE_HEADER, instanceName)
                .build();
    }

    /** Events waiting in the outbox, and since when (null if none). */
    public long pending() {
        return outbox.count();
    }

    public Instant oldestPending() {
        return outbox.oldestCreatedAt();
    }

    @PreDestroy
    void shutdown() {
        thread.shutdownNow();
    }
}
