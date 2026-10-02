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

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replication end to end ("redundancy-based fault tolerance", P1 p.2): three real instances, each flight kept on two
 * of them. The instance holding a flight is stopped, its flights stay readable from the others, it misses changes
 * while down, and it catches up when it comes back with an empty database. The tests run in order.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReplicationIntegrationTest {

    private static final String AIRCRAFT = "CS-TPA";

    private static final Map<String, Integer> ports = new LinkedHashMap<>();
    private static final Map<String, ConfigurableApplicationContext> running = new LinkedHashMap<>();
    private static String cluster;
    private static String token;

    // the roles for AIRCRAFT, from GET /api/cluster/owner
    private static String owner;
    private static String backup;
    private static String other;   // holds no copy of AIRCRAFT

    private static String bookedViaOwner;
    private static String bookedWhileOwnerDown;
    private static int totalFlightsBefore;

    private static final RestClient http = RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
            .build();

    @BeforeAll
    static void startThreeInstances() throws IOException {
        List<String> entries = new ArrayList<>();
        for (String name : List.of("instance1", "instance2", "instance3")) {
            int port = freePort();
            ports.put(name, port);
            entries.add(name + "=" + url(name));
        }
        cluster = String.join(",", entries);
        ports.keySet().forEach(ReplicationIntegrationTest::start);
        token = login(url("instance1"));

        @SuppressWarnings("unchecked")
        List<String> replicas = (List<String>) get("instance1", "/api/cluster/owner/" + AIRCRAFT).getBody().get("replicas");
        owner = replicas.get(0);
        backup = replicas.get(1);
        other = ports.keySet().stream().filter(n -> !replicas.contains(n)).findFirst().orElseThrow();
    }

    private static void start(String name) {
        running.put(name, new SpringApplicationBuilder(FlightOperationsApplication.class)
                .profiles(name, "stub")
                .run("--server.port=" + ports.get(name),
                        "--flightops.cluster=" + cluster,
                        "--flightops.replication.factor=2",
                        "--flightops.replication.retry-interval-ms=200",
                        "--flightops.replication.sync-interval-ms=0",            // only the startup catch-up
                        "--sidis.resilience.health-check-interval-ms=500"));     // notice a restarted peer quickly
    }

    private static void stop(String name) {
        running.remove(name).close();
    }

    @AfterAll
    static void stopAll() {
        running.values().forEach(ConfigurableApplicationContext::close);
    }

    @Test
    @Order(1)
    void everyInstanceSeesAllSampleFlightsOnce() {
        // each sample flight is stored twice, but lists merge the copies
        for (String name : ports.keySet()) {
            assertThat(totalFlights(name)).isEqualTo(10);
        }
        totalFlightsBefore = 10;
    }

    @Test
    @Order(2)
    void bookingIsMadeOnTheOwnerAndCopiedToTheBackup() {
        ResponseEntity<Map> created = book(other, LocalDateTime.now().plusDays(700).withNano(0));

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getFirst("X-Stored-On")).isEqualTo(owner);
        assertThat(created.getHeaders().getFirst("X-Replicas")).isEqualTo(owner + "," + backup);
        bookedViaOwner = (String) created.getBody().get("flightNumber");

        // the copy arrives on the backup shortly after (eventual consistency)
        await(() -> "local".equals(dataSource(backup, bookedViaOwner)));
        assertThat(dataSource(owner, bookedViaOwner)).isEqualTo("local");
        assertThat(dataSource(other, bookedViaOwner)).startsWith("peer:");   // the third instance has no copy
    }

    @Test
    @Order(3)
    void withTheOwnerDownItsFlightsAreStillReadable() {
        stop(owner);

        ResponseEntity<Map> flight = get(other, "/api/scheduled-flights/" + bookedViaOwner);
        assertThat(flight.getStatusCode().value()).isEqualTo(200);
        assertThat(flight.getHeaders().getFirst("X-Data-Source")).isEqualTo("peer:" + url(backup));

        // lists are complete: one instance is down, but every flight has a copy on a running one
        ResponseEntity<Map> list = get(other, "/api/scheduled-flights/aircraft/" + AIRCRAFT);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getHeaders().getFirst("X-Unreachable-Peers")).isEqualTo("1");
        assertThat(list.getHeaders().containsKey("X-Partial-Result")).isFalse();
        assertThat(totalFlights(other)).isEqualTo(totalFlightsBefore + 1);
        assertThat(totalFlights(backup)).isEqualTo(totalFlightsBefore + 1);
    }

    @Test
    @Order(4)
    void withTheOwnerDownBookingsGoToTheBackup() {
        ResponseEntity<Map> created = book(other, LocalDateTime.now().plusDays(800).withNano(0));

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getFirst("X-Stored-On")).isEqualTo(backup);
        bookedWhileOwnerDown = (String) created.getBody().get("flightNumber");
    }

    @Test
    @Order(5)
    void withTheOwnerDownCancellingWorksOnTheBackupsCopy() {
        ResponseEntity<Map> cancelled = http.patch().uri(url(other) + "/api/scheduled-flights/" + bookedViaOwner + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().toEntity(Map.class);

        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody()).containsEntry("status", "CANCELED");
        // both changes wait in the backup's outbox for the owner
        assertThat(backlog(backup).get(owner)).isEqualTo(2);
    }

    @Test
    @Order(6)
    void ownerComesBackWithAnEmptyDatabaseAndCatchesUp() {
        start(owner);   // in-memory database: everything it had is gone

        // before reporting ready it fetched its flights from the others, including what changed while it was down
        ResponseEntity<Map> cancelled = get(owner, "/api/scheduled-flights/" + bookedViaOwner);
        assertThat(cancelled.getHeaders().getFirst("X-Data-Source")).isEqualTo("local");
        assertThat(cancelled.getBody()).containsEntry("status", "CANCELED");
        assertThat(dataSource(owner, bookedWhileOwnerDown)).isEqualTo("local");
        assertThat(totalFlights(owner)).isEqualTo(totalFlightsBefore + 1);   // the cancelled one no longer counts

        // and the backup's queued copies are delivered once it sees the owner again
        await(() -> backlog(backup).isEmpty());
    }

    @Test
    @Order(7)
    void bookingsGoToTheOwnerAgainOnceItIsBack() {
        await(() -> {
            ResponseEntity<Map> created = book(other, LocalDateTime.now().plusDays(900 + (long) (Math.random() * 50)).withNano(0));
            return created.getStatusCode().value() == 201 && owner.equals(created.getHeaders().getFirst("X-Stored-On"));
        });
    }

    // helpers

    private static String url(String name) {
        return "http://localhost:" + ports.get(name);
    }

    private static String login(String baseUrl) {
        Map<?, ?> body = http.post().uri(baseUrl + "/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "admin", "password", "admin123"))
                .retrieve().body(Map.class);
        return (String) body.get("token");
    }

    private static ResponseEntity<Map> get(String instance, String path) {
        return http.get().uri(url(instance) + path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().toEntity(Map.class);
    }

    private static ResponseEntity<Map> book(String via, LocalDateTime departure) {
        return http.post().uri(url(via) + "/api/scheduled-flights")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("routeId", "route-opo-lis", "aircraftRegistration", AIRCRAFT,
                        "departureTime", departure.toString(), "arrivalTime", departure.plusMinutes(45).toString()))
                .retrieve().toEntity(Map.class);
    }

    private static String dataSource(String instance, String flightNumber) {
        ResponseEntity<Map> res = get(instance, "/api/scheduled-flights/" + flightNumber);
        return res.getStatusCode().value() == 200 ? res.getHeaders().getFirst("X-Data-Source") : "HTTP " + res.getStatusCode().value();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> backlog(String instance) {
        return (Map<String, Object>) get(instance, "/api/cluster/health").getBody().get("replicationBacklog");
    }

    @SuppressWarnings("unchecked")
    private static int totalFlights(String instance) {
        Map<String, Object> body = get(instance, "/api/aircraft-utilization").getBody();
        Map<String, Object> embedded = (Map<String, Object>) body.get("_embedded");
        List<Map<String, Object>> list = (List<Map<String, Object>>) embedded.get("utilizations");
        return list.stream().mapToInt(m -> ((Number) m.get("totalFlights")).intValue()).sum();
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met within 15 s").isLessThan(deadline);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
