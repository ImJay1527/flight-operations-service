package pt.isep.sidis.flightops.readmodel;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pt.isep.sidis.flightops.api.dto.AircraftUtilizationDTO;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.common.crypto.FieldEncryption;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read model against a real MongoDB (Testcontainers; skipped when Docker is not running): idempotent projection,
 * newest revision wins, reports recomputed, queries, encryption at rest, rebuild.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoReadModelTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private static MongoClient client;
    private MongoTemplate mongo;
    private MongoReadModel readModel;
    private final LocalDateTime dep = LocalDateTime.of(2027, 3, 10, 8, 0);

    @BeforeAll
    static void connect() {
        client = MongoClients.create(MONGO.getConnectionString());
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void freshDatabase() {
        mongo = new MongoTemplate(client, "read_" + UUID.randomUUID().toString().replace("-", ""));
        readModel = new MongoReadModel(mongo, new FieldEncryption("9BvH3kXoOq3m1o9mJmPFjB6m4cO1kz3Hqk2iY7G1M8o="));
    }

    private FlightView flight(String number, String registration, String route, String origin, LocalDateTime departure,
                              String status, long revision) {
        return new FlightView(number, route, registration, "A320neo", origin, "LIS", 300, 4.0,
                departure, departure.plusMinutes(90), status, revision);
    }

    @Test
    void projectedFlightCanBeReadBack() {
        FlightView f = flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "SCHEDULED", 1);
        readModel.project(f);

        assertThat(readModel.flight("F-1")).contains(f);
        assertThat(readModel.flightsOfAircraft("CS-TPA")).containsExactly(f);
    }

    @Test
    void activeFlightsAreEveryAircraftsFlightsWithoutTheCancelledOnes() {
        FlightView later = flight("F-2", "CS-TPB", "route-lis-mad", "LIS", dep.plusDays(1), "SCHEDULED", 1);
        FlightView first = flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "COMPLETED", 2);
        readModel.project(later);
        readModel.project(first);
        readModel.project(flight("F-3", "CS-TPA", "route-opo-lis", "OPO", dep.plusDays(2), "CANCELED", 2));

        assertThat(readModel.activeFlights()).containsExactly(first, later);   // by departure time
    }

    @Test
    void sensitiveFieldsAreEncryptedAtRest() {
        readModel.project(flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "SCHEDULED", 1));

        Document raw = mongo.getCollection(MongoReadModel.FLIGHTS).find().first();
        assertThat(raw.getString("registration")).startsWith(FieldEncryption.PREFIX).doesNotContain("CS-TPA");
        assertThat(raw.getString("origin")).startsWith(FieldEncryption.PREFIX);
        assertThat(raw.getString("routeId")).startsWith(FieldEncryption.PREFIX);
    }

    @Test
    void newerRevisionReplacesAndOlderOneIsIgnored() {
        readModel.project(flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "SCHEDULED", 1));
        readModel.project(flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "CANCELED", 2));
        readModel.project(flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "SCHEDULED", 1));   // late, old

        assertThat(readModel.flight("F-1")).get().extracting(FlightView::status).isEqualTo("CANCELED");
    }

    @Test
    void sameChangeTwiceIsCountedOnce() {
        FlightView f = flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "SCHEDULED", 1);
        readModel.project(f);
        readModel.project(f);   // at-least-once delivery: duplicates happen

        assertThat(readModel.utilization("CS-TPA")).get()
                .extracting(AircraftUtilizationDTO::getTotalFlights).isEqualTo(1L);
    }

    @Test
    void reportsAreRecomputedWhenAFlightIsCancelled() {
        readModel.project(flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "SCHEDULED", 1));
        readModel.project(flight("F-2", "CS-TPA", "route-opo-lis", "OPO", dep.plusDays(1), "SCHEDULED", 1));
        assertThat(readModel.utilization("CS-TPA").get().getTotalFlights()).isEqualTo(2);
        assertThat(readModel.aircraftFuel("CS-TPA").get().getTotalDistanceFlownKm()).isEqualTo(600);
        assertThat(readModel.routeFuel("route-opo-lis").get().getFlightCount()).isEqualTo(2);

        readModel.project(flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "CANCELED", 2));
        assertThat(readModel.utilization("CS-TPA").get().getTotalFlights()).isEqualTo(1);
        assertThat(readModel.utilization("CS-TPA").get().getTotalFlightHours()).isEqualTo(1.5);

        readModel.project(flight("F-2", "CS-TPA", "route-opo-lis", "OPO", dep.plusDays(1), "CANCELED", 2));
        assertThat(readModel.utilization("CS-TPA")).isEmpty();          // no non-cancelled flights: not listed
        assertThat(readModel.routeFuel("route-opo-lis")).isEmpty();
        assertThat(readModel.utilization()).isEmpty();
    }

    @Test
    void utilizationIsGroupedByMonth() {
        readModel.project(flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "SCHEDULED", 1));
        readModel.project(flight("F-2", "CS-TPA", "route-opo-lis", "OPO", dep.plusMonths(1), "SCHEDULED", 1));
        readModel.project(flight("F-3", "CS-TPB", "route-opo-lis", "OPO", dep, "SCHEDULED", 1));

        List<AircraftUtilizationDTO> all = readModel.utilization();
        assertThat(all).extracting(AircraftUtilizationDTO::getRegistrationNumber).containsExactly("CS-TPA", "CS-TPB");
        assertThat(all.get(0).getUtilizationByPeriod()).hasSize(2);
    }

    @Test
    void departuresAreTheNonCancelledFlightsOfTheAirportInTheWindowByTime() {
        readModel.project(flight("LATER", "CS-TPA", "route-opo-lis", "OPO", dep.plusHours(5), "SCHEDULED", 1));
        readModel.project(flight("SOON", "CS-TPB", "route-opo-lis", "OPO", dep.plusHours(1), "SCHEDULED", 1));
        readModel.project(flight("CANCELED", "CS-TPC", "route-opo-lis", "OPO", dep.plusHours(2), "CANCELED", 2));
        readModel.project(flight("OTHER-AIRPORT", "CS-TPD", "route-lis-mad", "LIS", dep.plusHours(2), "SCHEDULED", 1));
        readModel.project(flight("TOO-LATE", "CS-TPE", "route-opo-lis", "OPO", dep.plusHours(30), "SCHEDULED", 1));

        assertThat(readModel.departures("OPO", dep, dep.plusHours(24)))
                .extracting(FlightView::flightNumber).containsExactly("SOON", "LATER");
    }

    @Test
    void completeRebuildRemovesFlightsNobodyHoldsButKeepsNewProjections() throws InterruptedException {
        readModel.project(flight("GONE", "CS-TPA", "route-opo-lis", "OPO", dep, "SCHEDULED", 1));
        Thread.sleep(10);   // MongoDB keeps milliseconds
        Instant started = Instant.now();
        Thread.sleep(10);
        readModel.project(flight("JUST-BOOKED", "CS-TPB", "route-opo-lis", "OPO", dep, "SCHEDULED", 1));

        int changed = readModel.rebuild(List.of(flight("KEPT", "CS-TPC", "route-opo-lis", "OPO", dep, "SCHEDULED", 1)),
                true, started);

        assertThat(changed).isEqualTo(1);
        assertThat(readModel.flightNumbers()).containsExactlyInAnyOrder("KEPT", "JUST-BOOKED");
        assertThat(readModel.utilization()).extracting(AircraftUtilizationDTO::getRegistrationNumber)
                .containsExactly("CS-TPB", "CS-TPC");
    }

    @Test
    void incompleteRebuildRemovesNothing() {
        readModel.project(flight("MAYBE-ON-A-DOWN-PEER", "CS-TPA", "route-opo-lis", "OPO", dep, "SCHEDULED", 1));

        readModel.rebuild(List.of(), false, Instant.now());

        assertThat(readModel.flightNumbers()).containsExactly("MAYBE-ON-A-DOWN-PEER");
    }

    @Test
    void rebuildKeepsTheNewestOfSeveralCopies() {
        readModel.rebuild(List.of(
                flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "CANCELED", 2),
                flight("F-1", "CS-TPA", "route-opo-lis", "OPO", dep, "SCHEDULED", 1)), true, Instant.now());

        assertThat(readModel.flight("F-1")).get().extracting(FlightView::status).isEqualTo("CANCELED");
    }
}
