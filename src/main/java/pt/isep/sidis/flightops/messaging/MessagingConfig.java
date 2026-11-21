package pt.isep.sidis.flightops.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.amqp.ConnectionFactoryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The broker topology (RabbitMQ, AMQP 0-9-1), declared on first connection and again after every reconnect:
 * <pre>
 *   aisafe.events (topic, durable) --flight.#----> flight.events.&lt;instance&gt;   one queue per instance
 *                                  --aircraft.#--/                            (Aircraft &amp; Maintenance's events)
 *                                  --airport.#---/                            (Airports &amp; Routes' events)
 *                                  --route.#-----/
 *   aisafe.dlx    (topic, durable) --flight.events.dlq--> flight.events.dlq     failed / expired messages
 * </pre>
 * One queue per instance is the publish/subscribe pattern (RabbitMQ tutorial 3, "state synchronization"): every
 * instance gets every event. A queue shared by the instances would be the work-queue pattern (tutorial 2), where
 * each event reaches only one of them. Every instance needs every event of the other services too, for its own
 * copy of the aircraft, airports and routes ({@code LocalAircraftCopy}, {@code LocalRouteCopy}). The exchange and
 * the dead-letter exchange are the ones of the system contract (docs/service-contracts.md); the other services bind
 * their own queues to them.
 */
@Configuration
@ConditionalOnProperty(name = "flightops.messaging.enabled", havingValue = "true")
@EnableConfigurationProperties(MessagingConfig.Settings.class)
public class MessagingConfig {

    /**
     * A broker that stops answering (paused, network cut) must not hold a thread for long: an AMQP call gives up
     * after {@code rpc-timeout-ms} (the client's default is 10 minutes).
     */
    @Bean
    ConnectionFactoryCustomizer brokerTimeouts(Settings settings) {
        return factory -> factory.setChannelRpcTimeout((int) settings.rpcTimeoutMs());
    }

    @Bean
    TopicExchange eventsExchange(Settings settings) {
        return new TopicExchange(settings.exchange(), true, false);
    }

    @Bean
    TopicExchange deadLetterExchange(Settings settings) {
        return new TopicExchange(settings.deadLetterExchange(), true, false);
    }

    /**
     * This instance's queue. Durable, so events wait while the instance is down. Limits (RabbitMQ best practices):
     * at most {@code queue-max-length} messages, each kept {@code message-ttl-ms}, and the queue itself goes away
     * after {@code queue-expires-ms} without a consumer (an instance removed for good). Messages that fail, expire or
     * overflow go to the dead-letter exchange.
     */
    @Bean
    Queue flightEventsQueue(Settings settings) {
        return QueueBuilder.durable(settings.queue())
                .deadLetterExchange(settings.deadLetterExchange())
                .deadLetterRoutingKey(settings.deadLetterQueue())
                .maxLength(settings.queueMaxLength())
                .ttl((int) settings.messageTtlMs())
                .expires((int) settings.queueExpiresMs())
                .build();
    }

    @Bean
    Queue deadLetterQueue(Settings settings) {
        return QueueBuilder.durable(settings.deadLetterQueue())
                .maxLength(settings.queueMaxLength())
                .ttl((int) settings.deadLetterTtlMs())
                .build();
    }

    @Bean
    Binding flightEventsBinding(Queue flightEventsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(flightEventsQueue).to(eventsExchange).with("flight.#");
    }

    @Bean
    Binding aircraftEventsBinding(Queue flightEventsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(flightEventsQueue).to(eventsExchange).with("aircraft.#");
    }

    @Bean
    Binding airportEventsBinding(Queue flightEventsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(flightEventsQueue).to(eventsExchange).with("airport.#");
    }

    @Bean
    Binding routeEventsBinding(Queue flightEventsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(flightEventsQueue).to(eventsExchange).with("route.#");
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange, Settings settings) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(settings.deadLetterQueue());
    }

    /** flightops.messaging.* (application.properties). */
    @ConfigurationProperties("flightops.messaging")
    public record Settings(String exchange, String deadLetterExchange, String queue, String deadLetterQueue,
                           long queueMaxLength, long messageTtlMs, long queueExpiresMs, long deadLetterTtlMs,
                           long relayIntervalMs, long confirmTimeoutMs, long rpcTimeoutMs) {
    }
}
