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
import pt.isep.sidis.flightops.common.metrics.FlightOpsMetrics;
import pt.isep.sidis.flightops.readmodel.ReadModel;

import java.io.IOException;

/**
 * Consumes this instance's queue: the flight changes made on the other instances (pushed by the broker within
 * milliseconds) go into this instance's read model. The message is acknowledged after it has been processed
 * (Spring AMQP's AUTO mode: when this method returns). Processing is idempotent - the newest revision wins in the
 * read model - so a duplicate or an event that arrives late changes nothing.
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
    private final ObjectMapper json;
    private final FlightOpsMetrics metrics;
    private final String instanceName;

    public FlightEventListener(ReadModel readModel, ObjectMapper json, FlightOpsMetrics metrics,
                               @Value("${flightops.instance-name}") String instanceName) {
        this.readModel = readModel;
        this.json = json;
        this.metrics = metrics;
        this.instanceName = instanceName;
    }

    @RabbitListener(id = "flight-events", queues = "#{flightEventsQueue.name}")
    public void on(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
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
