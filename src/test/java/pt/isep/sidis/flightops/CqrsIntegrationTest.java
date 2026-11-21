package pt.isep.sidis.flightops;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CQRS end to end: two real instances with the "cqrs" profile, each with its own write database (H2) and its own
 * read database (a MongoDB database of its own). Replication is off, so the write side is split between the two
 * instances (each aircraft's flights on one of them), while each read model has every flight. Needs Docker
 * (Testcontainers); skipped when it is not running. The tests run in order.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CqrsIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private static ConfigurableApplicationContext instance1;
    private static ConfigurableApplicationContext instance2;
    private static String url1;
    private static String url2;
    private static String token;
    private static String bookedOn2;

    private static final RestClient http = RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
            .build();

    @BeforeAll
    static void startTwoInstances() throws IOException {
        int port1 = freePort();
        int port2 = freePort();
        url1 = "http://localhost:" + port1;
        url2 = "http://localhost:" + port2;
        String cluster = "instance1=" + url1 + ",instance2=" + url2;
        instance1 = start("instance1", port1, cluster);
        instance2 = start("instance2", port2, cluster);
        token = login(url1);
    }

    private static ConfigurableApplicationContext start(String profile, int port, String cluster) {
        return new SpringApplicationBuilder(FlightOperationsApplication.class)
                .profiles(profile, "stub", "cqrs")
                .run("--server.port=" + port,
                        "--flightops.cluster=" + cluster,
                        "--flightops.replication.factor=1",
                        "--spring.data.mongodb.uri=" + MONGO.getConnectionString() + "/read_" + profile
                                + "?serverSelectionTimeoutMS=2000&connectTimeoutMS=2000&socketTimeoutMS=3000",
                        "--flightops.read-model.sync-interval-ms=1000",
                        "--flightops.read-model.retry-interval-ms=500",
                        "--sidis.resilience.health-check-interval-ms=60000");
    }

    @AfterAll
    static void stop() {
        if (instance1 != null) instance1.close();
        if (instance2 != null) instance2.close();
    }

    @Test
    @Order(1)
    void eachReadModelHasEveryFlightAlthoughTheWriteSideIsSplit() {
        // instance 2 started after instance 1's first sync, so instance 1 began STALE ("1 instance unreachable") and
        // completes its read model on a later sync
        await(() -> readModelAnswers(url1) && readModelAnswers(url2));
        ResponseEntity<Map> res = get(url2 + "/api/aircraft-utilization");

        assertThat(res.getHeaders().getFirst("X-Read-Model")).isEqualTo("READY");
        assertThat(res.getHeaders().getFirst("X-Read-Model-Updated")).isNotBlank();
        assertThat(totalFlights(url1)).isEqualTo(10);
        assertThat(totalFlights(url2)).isEqualTo(10);
    }

    @Test
    @Order(2)
    void readYourOwnWriteRightAway() {
        String flight = flightNumber(book(url1, "CS-TPA", 40));   // CS-TPA's owner is instance1: booked here

        ResponseEntity<Map> res = get(url1 + "/api/scheduled-flights/" + flight);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("X-Data-Source")).isEqualTo("read-model");
    }

    @Test
    @Order(3)
    void bookingForwardedToTheOwnerIsReadBackAtOnceWhereItWasReceived() {
        String flight = flightNumber(book(url1, "CS-TPC", 41));   // owner instance2: forwarded

        ResponseEntity<Map> res = get(url1 + "/api/scheduled-flights/" + flight);
        assertThat(res.getHeaders().getFirst("X-Data-Source")).isEqualTo("read-model");
    }

    @Test
    @Order(4)
    void changeMadeOnAnotherInstanceReachesThisReadModel() {
        // booked on instance 2 for its own aircraft: instance 1 is not involved at all (eventual consistency)
        bookedOn2 = flightNumber(book(url2, "CS-TPC", 42));

        await(() -> flightNumbers(get(url1 + "/api/scheduled-flights/aircraft/CS-TPC")).contains(bookedOn2));
        ResponseEntity<Map> res = get(url1 + "/api/scheduled-flights/aircraft/CS-TPC");
        assertThat(res.getHeaders().getFirst("X-Read-Side")).isEqualTo("read-model");
    }

    @Test
    @Order(5)
    void cancellationUpdatesTheReportsOnBothInstances() {
        await(() -> totalFlights(url2) == totalFlights(url1));   // both have seen the bookings above
        int before = totalFlights(url1);
        ResponseEntity<Map> cancelled = http.patch().uri(url2 + "/api/scheduled-flights/" + bookedOn2 + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().toEntity(Map.class);
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);

        assertThat(totalFlights(url2)).isEqualTo(before - 1);       // instance 2 made the change: at once
        await(() -> totalFlights(url1) == before - 1);              // instance 1: eventually
    }

    @Test
    @Order(6)
    @SuppressWarnings("unchecked")
    void unicornsAreAQueryOfTheReadModel() {
        ResponseEntity<List> res = http.get().uri(url2 + "/api/v2/unicorns")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().toEntity(List.class);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("X-Read-Side")).isEqualTo("read-model");
        List<Map<String, Object>> moods = res.getBody();
        // every aircraft with flights, also those whose flights instance 2 doesn't hold (write side split)
        assertThat(moods).extracting(m -> m.get("registration")).containsExactly("CS-TPA", "CS-TPB", "CS-TPC");
        // CS-TPB's only flight leaves in 5 days (sample data)
        assertThat(moods.get(1).get("mood")).isEqualTo("HAPPY");

        // the same query from the write side gives the same moods
        ResponseEntity<List> writeSide = http.get().uri(url2 + "/api/v2/unicorns")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("X-Read-Side", "write-side")
                .retrieve().toEntity(List.class);
        assertThat(writeSide.getHeaders().getFirst("X-Read-Side")).isEqualTo("write-side");
        assertThat(((List<Map<String, Object>>) writeSide.getBody())).extracting(m -> m.get("mood"))
                .isEqualTo(moods.stream().map(m -> m.get("mood")).toList());

        // an airplane without flights is BORED; one Aircraft & Maintenance doesn't know is 404
        assertThat(get(url1 + "/api/v2/unicorns/CS-TPD").getBody().get("mood")).isEqualTo("BORED");
        assertThat(get(url1 + "/api/v2/unicorns/CS-NOPE").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @Order(7)
    void readDatabaseDownMeansTheWriteSideAnswersUntilItIsBack() {
        MONGO.getDockerClient().pauseContainerCmd(MONGO.getContainerId()).exec();
        try {
            ResponseEntity<Map> res = get(url1 + "/api/scheduled-flights/aircraft/CS-TPA");
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getHeaders().getFirst("X-Read-Side")).isEqualTo("write-side");
            assertThat(res.getHeaders().getFirst("X-Read-Model")).startsWith("STALE");
        } finally {
            MONGO.getDockerClient().unpauseContainerCmd(MONGO.getContainerId()).exec();
        }
        await(() -> readModelAnswers(url1));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean readModelAnswers(String baseUrl) {
        return "read-model".equals(get(baseUrl + "/api/aircraft-utilization").getHeaders().getFirst("X-Read-Side"));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 20 s");
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

    private static ResponseEntity<Map> book(String baseUrl, String registration, int daysAhead) {
        LocalDateTime departure = LocalDateTime.now().plusDays(daysAhead).withNano(0);
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

    @SuppressWarnings("unchecked")
    private static List<String> flightNumbers(ResponseEntity<Map> list) {
        Map<String, Object> embedded = (Map<String, Object>) list.getBody().get("_embedded");
        if (embedded == null) {
            return List.of();
        }
        List<Map<String, Object>> flights = (List<Map<String, Object>>) embedded.values().iterator().next();
        return flights.stream().map(f -> (String) f.get("flightNumber")).toList();
    }

    @SuppressWarnings("unchecked")
    private static int totalFlights(String baseUrl) {
        Map<String, Object> body = get(baseUrl + "/api/aircraft-utilization").getBody();
        Map<String, Object> embedded = (Map<String, Object>) body.get("_embedded");
        if (embedded == null) {
            return 0;
        }
        List<Map<String, Object>> list = (List<Map<String, Object>>) embedded.get("utilizations");
        return list.stream().mapToInt(m -> ((Number) m.get("totalFlights")).intValue()).sum();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
