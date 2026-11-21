package pt.isep.sidis.flightops;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CQRS with separate processes: two command instances (write database + read database each) and a query instance
 * (read database only, profile "query"), one real broker. The query instance is not in the command instances' list:
 * it holds no flights and takes no commands, its read model learns the changes from the broker (the pull runs once an
 * hour here) and is rebuilt from the command instances when a query instance starts. Needs Docker (Testcontainers);
 * skipped when it is not running. The tests run in order.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryInstanceIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

    private static final List<ConfigurableApplicationContext> running = new ArrayList<>();
    private static String command1;
    private static String command2;
    private static String commandList;
    private static String query1;
    private static ConfigurableApplicationContext query1Context;
    private static String token;
    private static CachingConnectionFactory brokerConnection;
    private static RabbitAdmin broker;

    private static final RestClient http = RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
            .build();

    @BeforeAll
    static void startTwoCommandInstancesAndOneQueryInstance() throws IOException {
        int port1 = freePort();
        int port2 = freePort();
        command1 = "http://localhost:" + port1;
        command2 = "http://localhost:" + port2;
        commandList = "instance1=" + command1 + ",instance2=" + command2;
        start("instance1", port1, "instance1");
        start("instance2", port2, "instance2");
        int queryPort = freePort();
        query1 = "http://localhost:" + queryPort;
        query1Context = start("query1", queryPort, "query");
        token = login(command1);

        brokerConnection = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        brokerConnection.setUsername(RABBIT.getAdminUsername());
        brokerConnection.setPassword(RABBIT.getAdminPassword());
        broker = new RabbitAdmin(brokerConnection);
    }

    /** {@code profile}: instance1 / instance2 (command instances) or query (a query instance named {@code name}). */
    private static ConfigurableApplicationContext start(String name, int port, String profile) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(FlightOperationsApplication.class)
                .profiles(profile.equals("query") ? new String[] {"stub", "cqrs", "amqp", "query"}
                        : new String[] {profile, "stub", "cqrs", "amqp"})
                .run("--server.port=" + port,
                        "--flightops.instance-name=" + name,
                        "--flightops.cluster=" + commandList,
                        "--spring.data.mongodb.uri=" + MONGO.getConnectionString() + "/read_" + name
                                + "?serverSelectionTimeoutMS=2000&connectTimeoutMS=2000&socketTimeoutMS=3000",
                        "--spring.rabbitmq.host=" + RABBIT.getHost(),
                        "--spring.rabbitmq.port=" + RABBIT.getAmqpPort(),
                        "--spring.rabbitmq.username=" + RABBIT.getAdminUsername(),
                        "--spring.rabbitmq.password=" + RABBIT.getAdminPassword(),
                        "--flightops.messaging.status-interval-ms=500",
                        "--flightops.read-model.sync-interval-ms=3600000",
                        "--flightops.read-model.retry-interval-ms=500",
                        "--sidis.resilience.health-check-interval-ms=60000");
        running.add(context);
        return context;
    }

    @AfterAll
    static void stop() {
        running.forEach(ConfigurableApplicationContext::close);
        if (brokerConnection != null) brokerConnection.destroy();
    }

    @Test
    @Order(1)
    void theQueryInstanceIsNotAMemberAndListensOnItsOwnQueue() {
        await(() -> readModelAnswers(query1));

        Map<?, ?> health = get(query1 + "/api/cluster/health").getBody();
        assertThat(health.get("role")).isEqualTo("queries only");
        assertThat(health.get("name")).isEqualTo("query1");
        assertThat((List<Object>) health.get("members")).containsExactly("instance1", "instance2");
        // the command instances don't know it
        Map<?, ?> commandHealth = get(command1 + "/api/cluster/health").getBody();
        assertThat(commandHealth.get("role")).isEqualTo("commands and queries");
        assertThat((List<Object>) commandHealth.get("members")).containsExactly("instance1", "instance2");

        QueueInformation queue = broker.getQueueInfo("flight.events.query1");
        assertThat(queue).isNotNull();
        assertThat(queue.getConsumerCount()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void aBookingReachesTheQueryInstanceThroughTheBroker() {
        String flight = flightNumber(book(command1, "CS-TPA", LocalDateTime.now().plusDays(40).withNano(0)));

        // the pull runs once an hour here: only the event can have told the query instance
        await(() -> "read-model".equals(get(query1 + "/api/scheduled-flights/" + flight).getHeaders()
                .getFirst("X-Data-Source")));

        // its write side holds nothing: asked for it, it gets the flight from a command instance
        ResponseEntity<Map> fromWriteSide = http.get().uri(query1 + "/api/scheduled-flights/" + flight)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Read-Side", "write-side")
                .retrieve().toEntity(Map.class);
        assertThat(fromWriteSide.getStatusCode().value()).isEqualTo(200);
        assertThat(fromWriteSide.getHeaders().getFirst("X-Data-Source")).startsWith("peer:");

        ResponseEntity<String> unicorns = http.get().uri(query1 + "/api/v2/unicorns")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().toEntity(String.class);
        assertThat(unicorns.getStatusCode().value()).isEqualTo(200);
        assertThat(unicorns.getHeaders().getFirst("X-Read-Side")).isEqualTo("read-model");
        assertThat(unicorns.getBody()).contains("CS-TPA");
    }

    @Test
    @Order(3)
    void theQueryInstanceRefusesCommands() {
        ResponseEntity<Map> refused = http.post().uri(query1 + "/api/scheduled-flights")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(bookingRequest("CS-TPB", LocalDateTime.now().plusDays(41).withNano(0)))
                .retrieve().toEntity(Map.class);
        assertThat(refused.getStatusCode().value()).isEqualTo(405);
        assertThat(refused.getHeaders().getFirst(HttpHeaders.ALLOW)).isEqualTo("GET, HEAD");
        assertThat((String) refused.getBody().get("error")).contains("query instance").contains("instance1");

        ResponseEntity<Map> cancel = http.patch().uri(query1 + "/api/scheduled-flights/x/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().toEntity(Map.class);
        assertThat(cancel.getStatusCode().value()).isEqualTo(405);

        // without a token: 401 first, as on every instance
        ResponseEntity<Map> anonymous = http.post().uri(query1 + "/api/scheduled-flights")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toEntity(Map.class);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);

        // logging in works here too
        assertThat(login(query1)).isNotBlank();
    }

    @Test
    @Order(4)
    void commandsDontNeedTheQueryInstanceAndANewOneStartsWithTheWholeReadModel() throws IOException {
        query1Context.close();
        running.remove(query1Context);

        String flight = flightNumber(book(command2, "CS-TPC", LocalDateTime.now().plusDays(42).withNano(0)));

        // a new query instance: new queue, empty read database, so only the startup sync can give it that flight
        int port = freePort();
        String query2 = "http://localhost:" + port;
        start("query2", port, "query");
        assertThat(readModelAnswers(query2)).isTrue();   // READY before it reports ready
        ResponseEntity<Map> found = get(query2 + "/api/scheduled-flights/" + flight);
        assertThat(found.getStatusCode().value()).isEqualTo(200);
        assertThat(found.getHeaders().getFirst("X-Data-Source")).isEqualTo("read-model");
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

    private static Map<String, String> bookingRequest(String registration, LocalDateTime departure) {
        return Map.of("routeId", "route-opo-lis", "aircraftRegistration", registration,
                "departureTime", departure.toString(), "arrivalTime", departure.plusMinutes(45).toString());
    }

    private static ResponseEntity<Map> book(String baseUrl, String registration, LocalDateTime departure) {
        ResponseEntity<Map> res = http.post().uri(baseUrl + "/api/scheduled-flights")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(bookingRequest(registration, departure))
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
