package pt.isep.sidis.flightops.messaging;

import jakarta.annotation.PreDestroy;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The messaging side of this instance, for GET /api/cluster/health and the dashboard: is the broker reachable, how
 * many messages wait in this instance's queue and in the dead-letter queue ("monitor queue depth", RabbitMQ best
 * practices), and how many events wait in the outbox. The broker is asked in the background every few seconds, so
 * a broker that is down never slows these endpoints down.
 */
@Component
public class MessagingStatus {

    private final boolean enabled;
    private final ObjectProvider<EventRelay> relay;
    private final ObjectProvider<AmqpAdmin> admin;
    private final ObjectProvider<MessagingConfig.Settings> settings;
    private final ScheduledExecutorService thread;
    private volatile Broker broker = new Broker(null, null, null, null, null, null);

    public MessagingStatus(@Value("${flightops.messaging.enabled:false}") boolean enabled,
                           ObjectProvider<EventRelay> relay, ObjectProvider<AmqpAdmin> admin,
                           ObjectProvider<MessagingConfig.Settings> settings,
                           @Value("${flightops.messaging.status-interval-ms:5000}") long intervalMs) {
        this.enabled = enabled;
        this.relay = relay;
        this.admin = admin;
        this.settings = settings;
        if (enabled) {
            thread = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "messaging-status"));
            thread.scheduleWithFixedDelay(this::checkBroker, 0, intervalMs, TimeUnit.MILLISECONDS);
        } else {
            thread = null;
        }
    }

    /**
     * @param enabled      false without the "amqp" profile (no broker): the other fields are null
     * @param brokerUp     the broker answered the last check
     * @param queue        this instance's queue
     * @param queueDepth   messages waiting in it (not yet delivered to this instance)
     * @param consumers    consumers of it (1 while this instance is listening)
     * @param deadLetters  messages in the dead-letter queue (failed, expired or overflowed)
     * @param outbox       events of changes made here, not yet confirmed by the broker
     * @param oldestInOutbox when the oldest of them was made
     * @param lastError    why the last send or check failed (null if it worked)
     */
    public record Snapshot(boolean enabled, Boolean brokerUp, String queue, Long queueDepth, Integer consumers,
                           Long deadLetters, Long outbox, Instant oldestInOutbox, String lastError,
                           Instant checkedAt) {
    }

    private record Broker(Boolean up, Long queueDepth, Integer consumers, Long deadLetters, String error,
                          Instant checkedAt) {
    }

    public Snapshot snapshot() {
        if (!enabled) {
            return new Snapshot(false, null, null, null, null, null, null, null, null, null);
        }
        EventRelay r = relay.getObject();
        Broker b = broker;
        String error = r.lastError() != null ? r.lastError() : b.error();
        return new Snapshot(true, b.up(), settings.getObject().queue(), b.queueDepth(), b.consumers(),
                b.deadLetters(), r.pending(), r.oldestPending(), error, b.checkedAt());
    }

    private void checkBroker() {
        MessagingConfig.Settings s = settings.getObject();
        try {
            QueueInformation queue = admin.getObject().getQueueInfo(s.queue());
            QueueInformation dlq = admin.getObject().getQueueInfo(s.deadLetterQueue());
            broker = new Broker(true,
                    queue == null ? null : (long) queue.getMessageCount(),
                    queue == null ? null : queue.getConsumerCount(),
                    dlq == null ? null : (long) dlq.getMessageCount(),
                    null, Instant.now());
        } catch (RuntimeException e) {
            broker = new Broker(false, null, null, null, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    Instant.now());
        }
    }

    @PreDestroy
    void shutdown() {
        if (thread != null) {
            thread.shutdownNow();
        }
    }
}
