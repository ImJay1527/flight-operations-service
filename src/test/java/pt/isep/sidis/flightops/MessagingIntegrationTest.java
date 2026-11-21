package pt.isep.sidis.flightops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Messaging end to end: a real RabbitMQ broker and two instances with the "cqrs" and "amqp" profiles, each with its
 * own write database (H2), read database (MongoDB) and queue. The read models' pull (repair) is set to once an hour,
 * so a change can only reach the other instance's read model through the broker. A test queue bound like another
 * service's (Aircraft & Maintenance binds flight.#) checks what the other services receive, and the aircraft events
 * are sent the way that service sends them. Needs Docker
 * (Testcontainers); skipped when it is not running. The tests run in order.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MessagingIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

    private static final String OTHER_SERVICE_QUEUE = "test.other-service";

    private static ConfigurableApplicationContext instance1;
    private static ConfigurableApplicationContext instance2;
    private static String url1;
    private static String url2;
    private static String token;
    private static CachingConnectionFactory brokerConnection;
    private static RabbitAdmin broker;
    private static RabbitTemplate otherService;
    private static final ObjectMapper json = new ObjectMapper();

    private static final RestClient http = RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
            .build();

    @BeforeAll
    static void startTwoInstancesAndTheOtherService() throws IOException {
        int port1 = freePort();
        int port2 = freePort();
        url1 = "http://localhost:" + port1;
        url2 = "http://localhost:" + port2;
        String cluster = "instance1=" + url1 + ",instance2=" + url2;
        instance1 = start("instance1", port1, cluster);
        instance2 = start("instance2", port2, cluster);
        token = login(url1);

        brokerConnection = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        brokerConnection.setUsername(RABBIT.getAdminUsername());
        brokerConnection.setPassword(RABBIT.getAdminPassword());
        broker = new RabbitAdmin(brokerConnection);
        Queue queue = new Queue(OTHER_SERVICE_QUEUE, false, false, false);
        broker.declareQueue(queue);
        broker.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("aisafe.events")).with("flight.#"));
        otherService = new RabbitTemplate(brokerConnection);
    }

    private static ConfigurableApplicationContext start(String profile, int port, String cluster) {
        return new SpringApplicationBuilder(FlightOperationsApplication.class)
                .profiles(profile, "stub", "cqrs", "amqp")
                .run("--server.port=" + port,
                        "--flightops.cluster=" + cluster,
                        "--flightops.replication.factor=1",
                        "--spring.data.mongodb.uri=" + MONGO.getConnectionString() + "/read_" + profile
                                + "?serverSelectionTimeoutMS=2000&connectTimeoutMS=2000&socketTimeoutMS=3000",
                        "--spring.rabbitmq.host=" + RABBIT.getHost(),
                        "--spring.rabbitmq.port=" + RABBIT.getAmqpPort(),
                        "--spring.rabbitmq.username=" + RABBIT.getAdminUsername(),
                        "--spring.rabbitmq.password=" + RABBIT.getAdminPassword(),
                        "--spring.rabbitmq.requested-heartbeat=2s",
                        "--flightops.messaging.rpc-timeout-ms=2000",
                        "--flightops.messaging.confirm-timeout-ms=2000",
                        "--flightops.messaging.status-interval-ms=500",
                        "--flightops.read-model.sync-interval-ms=3600000",
                        "--flightops.read-model.retry-interval-ms=500",
                        "--sidis.resilience.health-check-interval-ms=60000");
    }

    @AfterAll
    static void stop() {
        if (instance1 != null) instance1.close();
        if (instance2 != null) instance2.close();
        if (broker != null) broker.deleteQueue(OTHER_SERVICE_QUEUE);
        if (brokerConnection != null) brokerConnection.destroy();
    }

    @Test
    @Order(1)
    void eachInstanceListensOnItsOwnQueue() {
        await(() -> readModelAnswers(url1) && readModelAnswers(url2));

        // publish/subscribe: one queue per instance, both bound to the same exchange
        for (String queue : new String[] {"flight.events.instance1", "flight.events.instance2"}) {
            QueueInformation info = broker.getQueueInfo(queue);
            assertThat(info).as(queue).isNotNull();
            assertThat(info.getConsumerCount()).as(queue).isEqualTo(1);
        }
        assertThat(broker.getQueueInfo("flight.events.dlq")).isNotNull();
        Map<?, ?> messaging = (Map<?, ?>) get(url1 + "/api/cluster/health").getBody().get("messaging");
        assertThat(messaging.get("enabled")).isEqualTo(true);
        await(() -> Boolean.TRUE.equals(((Map<?, ?>) get(url1 + "/api/cluster/health").getBody().get("messaging"))
                .get("brokerUp")));
    }

    @Test
    @Order(2)
    void bookingReachesTheOtherReadModelAndTheOtherServicesThroughTheBroker() throws Exception {
        drainOtherServiceQueue();
        // CS-TPC's owner is instance2: booked there, instance 1 is not involved at all
        String flight = flightNumber(book(url2, "CS-TPC", LocalDateTime.now().plusDays(50).withNano(0)));

        // the pull runs once an hour here: only the event can have told instance 1's read model
        await(() -> "read-model".equals(get(url1 + "/api/scheduled-flights/" + flight).getHeaders()
                .getFirst("X-Data-Source")));

        Message received = nextOtherServiceMessage();
        JsonNode event = json.readTree(received.getBody());
        assertThat(received.getMessageProperties().getReceivedRoutingKey()).isEqualTo("flight.scheduled");
        assertThat(received.getMessageProperties().getHeader("instance").toString()).isEqualTo("instance2");
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(event.get("eventId").asText());
        assertThat(event.get("eventType").asText()).isEqualTo("FlightScheduledEvent");
        assertThat(event.get("flightNumber").asText()).isEqualTo(flight);
        assertThat(event.get("registration").asText()).isEqualTo("CS-TPC");
        assertThat(event.has("durationHours")).isFalse();
    }

    @Test
    @Order(3)
    void completedFlightIsAnnouncedWithItsFlightHours() throws Exception {
        drainOtherServiceQueue();
        LocalDateTime departure = LocalDateTime.now().plusSeconds(2).withNano(0);
        String flight = flightNumber(book(url1, "CS-TPA", departure));   // CS-TPA's owner: instance1
        await(() -> LocalDateTime.now().isAfter(departure));
        nextOtherServiceMessage();   // its flight.scheduled

        ResponseEntity<Map> completed = patch(url1 + "/api/scheduled-flights/" + flight + "/complete");
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
        assertThat(completed.getBody().get("status")).isEqualTo("COMPLETED");

        Message received = nextOtherServiceMessage();
        JsonNode event = json.readTree(received.getBody());
        assertThat(received.getMessageProperties().getReceivedRoutingKey()).isEqualTo("flight.completed");
        assertThat(event.get("eventType").asText()).isEqualTo("FlightCompletedEvent");
        assertThat(event.get("registration").asText()).isEqualTo("CS-TPA");
        assertThat(event.get("durationHours").asDouble()).isEqualTo(0.75);
        await(() -> "COMPLETED".equals(get(url2 + "/api/scheduled-flights/" + flight).getBody().get("status")));

        assertThat(patch(url1 + "/api/scheduled-flights/" + flight + "/complete").getStatusCode().value())
                .isEqualTo(409);
    }

    @Test
    @Order(4)
    void withTheBrokerDownBookingsWorkAndTheirEventsWaitInTheOutbox() {
        String flight;
        RABBIT.getDockerClient().pauseContainerCmd(RABBIT.getContainerId()).exec();
        try {
            flight = flightNumber(book(url2, "CS-TPC", LocalDateTime.now().plusDays(51).withNano(0)));
            assertThat(outbox(url2)).isEqualTo(1);
        } finally {
            RABBIT.getDockerClient().unpauseContainerCmd(RABBIT.getContainerId()).exec();
        }
        // back: the relay sends it, and it reaches instance 1's read model
        await(() -> outbox(url2) == 0);
        await(() -> "read-model".equals(get(url1 + "/api/scheduled-flights/" + flight).getHeaders()
                .getFirst("X-Data-Source")));
    }

    @Test
    @Order(5)
    void aMessageThatCannotBeReadEndsInTheDeadLetterQueue() {
        Message poison = MessageBuilder.withBody("not a flight event".getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").build();
        otherService.send("aisafe.events", "flight.scheduled", poison);

        // each instance's queue got a copy: both are rejected after the retries, none is lost or retried forever
        await(() -> deadLetters() == 2);
        await(() -> Long.valueOf(2).equals(asLong(((Map<?, ?>) get(url1 + "/api/cluster/health").getBody()
                .get("messaging")).get("deadLetters"))));
        assertThat(broker.getQueueInfo("flight.events.instance1").getMessageCount()).isZero();
    }

    @Test
    @Order(6)
    void aircraftEventsOfTheOtherServiceReachEveryInstancesCopy() {
        // sent the way Aircraft & Maintenance sends them (its outbox: JSON body, eventId/eventType headers)
        sendAircraftEvent("aircraft.created", "AircraftCreatedEvent", """
                {"registration":"CS-EVX","modelCode":"A320neo","modelName":"Airbus","maxRange":6300.0,
                 "fuelCapacity":24000.0,"seatingCapacity":180,"status":"AVAILABLE","occurredAt":"%s"}""");

        // the built-in aircraft data doesn't know CS-EVX: only the event can have told the instances about it
        LocalDateTime departure = LocalDateTime.now().plusDays(60).withNano(0);
        await(() -> tryBook(url1, "CS-EVX", departure) == 201);
        for (String url : new String[] {url1, url2}) {   // each instance got it on its own queue
            await(() -> received(url, "aircraft.created") == 1);
        }

        sendAircraftEvent("aircraft.status.changed", "AircraftStatusChangedEvent", """
                {"registration":"CS-EVX","previousStatus":"AVAILABLE","newStatus":"UNDER_MAINTENANCE",
                 "occurredAt":"%s"}""");
        await(() -> tryBook(url2, "CS-EVX", departure.plusDays(1)) == 409);
    }

    // ------------------------------------------------------------------ helpers

    private static void sendAircraftEvent(String routingKey, String eventType, String body) {
        String eventId = UUID.randomUUID().toString();
        Message m = MessageBuilder.withBody(body.formatted(Instant.now()).getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setHeader("eventId", eventId).setHeader("eventType", eventType)
                .build();
        otherService.send("aisafe.events", routingKey, m);
    }

    @SuppressWarnings("unchecked")
    private static long received(String baseUrl, String routingKey) {
        Map<String, Object> messages = (Map<String, Object>) get(baseUrl + "/api/cluster/metrics").getBody()
                .get("messages");
        Map<String, Map<String, Number>> byKey = (Map<String, Map<String, Number>>) messages.get("byRoutingKey");
        Number n = byKey.getOrDefault("received", Map.of()).get(routingKey);
        return n == null ? 0 : n.longValue();
    }

    private static int tryBook(String baseUrl, String registration, LocalDateTime departure) {
        return http.post().uri(baseUrl + "/api/scheduled-flights")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("routeId", "route-opo-lis", "aircraftRegistration", registration,
                        "departureTime", departure.toString(), "arrivalTime", departure.plusMinutes(45).toString()))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    private static long deadLetters() {
        QueueInformation info = broker.getQueueInfo("flight.events.dlq");
        return info == null ? 0 : info.getMessageCount();
    }

    private static long outbox(String baseUrl) {
        Map<?, ?> messaging = (Map<?, ?>) get(baseUrl + "/api/cluster/health").getBody().get("messaging");
        return asLong(messaging.get("outbox"));
    }

    private static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static void drainOtherServiceQueue() {
        broker.purgeQueue(OTHER_SERVICE_QUEUE);
    }

    private static Message nextOtherServiceMessage() {
        Message m = otherService.receive(OTHER_SERVICE_QUEUE, 10_000);
        assertThat(m).as("an event on the other service's queue").isNotNull();
        return m;
    }

    private static boolean readModelAnswers(String baseUrl) {
        return "read-model".equals(get(baseUrl + "/api/aircraft-utilization").getHeaders().getFirst("X-Read-Side"));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 30 s");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static String login(String baseUrl) {
        Map<?, ?> body = http.post().uri(baseUrl + "/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "admin", "password", "admin123"))
                .retrieve().body(Map.class);
        return (String) body.get("token");
    }

    private static ResponseEntity<Map> get(String url) {
        return http.get().uri(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().toEntity(Map.class);
    }

    private static ResponseEntity<Map> patch(String url) {
        return http.patch().uri(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve()
                .toEntity(Map.class);
    }

    private static ResponseEntity<Map> book(String baseUrl, String registration, LocalDateTime departure) {
        ResponseEntity<Map> res = http.post().uri(baseUrl + "/api/scheduled-flights")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("routeId", "route-opo-lis", "aircraftRegistration", registration,
                        "departureTime", departure.toString(), "arrivalTime", departure.plusMinutes(45).toString()))
                .retrieve().toEntity(Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        return res;
    }

    private static String flightNumber(ResponseEntity<Map> created) {
        return (String) created.getBody().get("flightNumber");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
