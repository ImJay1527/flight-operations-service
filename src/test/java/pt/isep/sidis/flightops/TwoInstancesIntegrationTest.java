package pt.isep.sidis.flightops;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the distributed behaviour (PL3 p.15 "integration tests for end-to-end distributed behavior",
 * p.16 test cases): two REAL instances (profiles instance1 / instance2, each with its own in-memory database and the
 * "stub" profile for aircraft/route data) are started on free ports and talk to each other over HTTP.
 * The tests run in order; the last ones stop instance 2.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class TwoInstancesIntegrationTest {

    private static ConfigurableApplicationContext instance1;
    private static ConfigurableApplicationContext instance2;
    private static String url1;
    private static String url2;
    private static String token;
    private static String flightOn1;
    private static String flightOn2;

    private static final RestClient http = RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })   // let the tests look at 4xx/5xx
            .build();

    @BeforeAll
    static void startTwoInstances() throws IOException {
        int port1 = freePort();
        int port2 = freePort();
        url1 = "http://localhost:" + port1;
        url2 = "http://localhost:" + port2;
        String cluster = "instance1=" + url1 + ",instance2=" + url2;   // the same list for both instances
        instance1 = start("instance1", port1, cluster);
        instance2 = start("instance2", port2, cluster);
        token = login(url1);
    }

    private static ConfigurableApplicationContext start(String profile, int port, String cluster) {
        return new SpringApplicationBuilder(FlightOperationsApplication.class)
                .profiles(profile, "stub")
                .run("--server.port=" + port,
                        "--flightops.cluster=" + cluster,
                        "--sidis.resilience.health-check-interval-ms=60000");   // keep health checks out of the way
    }

    @AfterAll
    static void stop() {
        if (instance1 != null) instance1.close();
        if (instance2 != null && instance2.isActive()) instance2.close();
    }

    @Test
    @Order(1)
    void eachInstanceHoldsHalfTheSampleDataButBothSeeAll() {
        assertThat(totalFlights(url1)).isEqualTo(10);
        assertThat(totalFlights(url2)).isEqualTo(10);
    }

    @Test
    @Order(2)
    void localDataAccessDoesNotAskPeers() {
        // PL3 p.16 test 01
        flightOn1 = schedule(url1, "CS-TPA", 40);   // CS-TPA is owned by instance1
        ResponseEntity<Map> res = get(url1 + "/api/scheduled-flights/" + flightOn1);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("X-Data-Source")).isEqualTo("local");
        assertThat(res.getHeaders().getFirst("X-Instance")).isEqualTo("instance1");
    }

    @Test
    @Order(3)
    void dataOnlyOnInstance2IsForwardedThroughInstance1(CapturedOutput output) {
        // PL3 p.16 test 02: create data on instance 2 only, request it from instance 1
        flightOn2 = schedule(url2, "CS-TPC", 80);   // CS-TPC is owned by instance2
        ResponseEntity<Map> res = http.get().uri(url1 + "/api/scheduled-flights/" + flightOn2)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Request-Id", "trace-test-1")
                .retrieve().toEntity(Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("flightNumber", flightOn2);
        assertThat(res.getHeaders().getFirst("X-Data-Source")).isEqualTo("peer:" + url2);
        // the same request id shows up in the log of BOTH instances (structured logging, PL3 p.15)
        assertThat(output).contains("[instance1] [trace-test-1]").contains("[instance2] [trace-test-1]");
    }

    @Test
    @Order(4)
    void cancelIsForwardedToTheOwner() {
        ResponseEntity<Map> res = http.patch().uri(url1 + "/api/scheduled-flights/" + flightOn2 + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().toEntity(Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("status", "CANCELED");

        ResponseEntity<Map> again = http.patch().uri(url1 + "/api/scheduled-flights/" + flightOn2 + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().toEntity(Map.class);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @Order(5)
    void bookingIsStoredOnTheAircraftsOwner() {
        // P1 p.15 sharding by aircraft registration: CS-TPC belongs to instance2, the booking is sent to instance1
        assertThat(get(url1 + "/api/cluster/owner/CS-TPC").getBody()).containsEntry("owner", "instance2");

        ResponseEntity<Map> created = book(url1, "CS-TPC", LocalDateTime.now().plusDays(500).withNano(0));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getFirst("X-Instance")).isEqualTo("instance1");
        assertThat(created.getHeaders().getFirst("X-Stored-On")).isEqualTo("instance2");

        ResponseEntity<Map> onOwner = get(url2 + "/api/scheduled-flights/" + created.getBody().get("flightNumber"));
        assertThat(onOwner.getHeaders().getFirst("X-Data-Source")).isEqualTo("local");
    }

    @Test
    @Order(5)
    void simultaneousBookingsOnDifferentInstancesOnlyOneSucceeds() throws Exception {
        // both bookings end up on the owner of CS-TPC, whose per-aircraft lock lets only one of them through
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                LocalDateTime departure = LocalDateTime.now().plusDays(600 + round * 10L).withNano(0);
                CountDownLatch go = new CountDownLatch(1);
                Future<ResponseEntity<Map>> viaInstance1 = pool.submit(() -> { go.await(); return book(url1, "CS-TPC", departure); });
                Future<ResponseEntity<Map>> viaInstance2 = pool.submit(() -> { go.await(); return book(url2, "CS-TPC", departure); });
                go.countDown();
                List<Integer> statuses = List.of(viaInstance1.get().getStatusCode().value(), viaInstance2.get().getStatusCode().value());
                assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(201, 409);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(5)
    void completeListHasNoPartialHeader() {
        ResponseEntity<Map> res = get(url1 + "/api/scheduled-flights/aircraft/CS-TPA");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().containsKey("X-Partial-Result")).isFalse();
    }

    @Test
    @Order(5)
    void unknownIdIs404() {
        // PL3 p.16 test 05
        assertThat(get(url1 + "/api/scheduled-flights/does-not-exist").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @Order(6)
    void withInstance2DownLocalDataStillWorksAndRemoteDataIs404() {
        // PL3 p.16 test 03: stop instance 2
        instance2.close();

        assertThat(get(url1 + "/api/scheduled-flights/" + flightOn1).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> remote = get(url1 + "/api/scheduled-flights/" + flightOn2);
        assertThat(remote.getStatusCode().value()).isEqualTo(404);
        assertThat((String) remote.getBody().get("error")).contains("could not be reached");

        // lists still answer (200), but say they are incomplete (P1 p.12 "partial response scenarios")
        ResponseEntity<Map> list = get(url1 + "/api/scheduled-flights/aircraft/CS-TPA");
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getHeaders().getFirst("X-Partial-Result")).isEqualTo("true");
        assertThat(list.getHeaders().getFirst("X-Unreachable-Peers")).isEqualTo("1");
    }

    @Test
    @Order(7)
    void instance2IsReportedDownByTheCircuitBreaker() {
        ResponseEntity<Map> res = get(url1 + "/api/cluster/health");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) res.getBody().get("endpoints");

        assertThat(endpoints).filteredOn(e -> url2.equals(e.get("url")))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.get("circuit")).isEqualTo("OPEN");
                    assertThat(e.get("healthy")).isEqualTo(false);
                });
    }

    // ------------------------------------------------------------------------------------------------ helpers

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

    private static ResponseEntity<Map> book(String baseUrl, String registration, LocalDateTime departure) {
        return http.post().uri(baseUrl + "/api/scheduled-flights")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("routeId", "route-opo-lis", "aircraftRegistration", registration,
                        "departureTime", departure.toString(), "arrivalTime", departure.plusMinutes(45).toString()))
                .retrieve().toEntity(Map.class);
    }

    private static String schedule(String baseUrl, String registration, int daysAhead) {
        LocalDateTime departure = LocalDateTime.now().plusDays(daysAhead).withNano(0);
        Map<?, ?> created = http.post().uri(baseUrl + "/api/scheduled-flights")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("routeId", "route-opo-lis", "aircraftRegistration", registration,
                        "departureTime", departure.toString(), "arrivalTime", departure.plusMinutes(45).toString()))
                .retrieve().body(Map.class);
        return (String) created.get("flightNumber");
    }

    @SuppressWarnings("unchecked")
    private static int totalFlights(String baseUrl) {
        Map<String, Object> body = get(baseUrl + "/api/aircraft-utilization").getBody();
        Map<String, Object> embedded = (Map<String, Object>) body.get("_embedded");
        List<Map<String, Object>> list = (List<Map<String, Object>>) embedded.get("utilizations");
        return list.stream().mapToInt(m -> ((Number) m.get("totalFlights")).intValue()).sum();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
