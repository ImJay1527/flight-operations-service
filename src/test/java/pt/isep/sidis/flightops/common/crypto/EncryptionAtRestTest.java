package pt.isep.sidis.flightops.common.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;
import pt.isep.sidis.flightops.services.ScheduledFlightService;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What is really in the database (raw SQL), and that the app still works with it. */
@SpringBootTest
@ActiveProfiles("stub")
class EncryptionAtRestTest {

    @Autowired ScheduledFlightService service;
    @Autowired ScheduledFlightRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EncryptionMigration migration;

    @Test
    void routeAssignmentIsEncryptedInTheDatabase() {
        LocalDateTime departure = LocalDateTime.now().plusDays(2500).withNano(0);
        FlightView flight = service.scheduleFlight("route-opo-lis", "CS-TPC", departure, departure.plusMinutes(45)).flight();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT route_id, aircraft_registration, aircraft_model, origin_iata, destination_iata, status " +
                "FROM scheduled_flight WHERE flight_number = ?", flight.flightNumber());

        assertThat(row.get("ROUTE_ID").toString()).startsWith("enc:v1:").doesNotContain("opo");
        assertThat(row.get("AIRCRAFT_REGISTRATION").toString()).startsWith("enc:v1:").doesNotContain("CS-TPC");
        assertThat(row.get("AIRCRAFT_MODEL").toString()).startsWith("enc:v1:");
        assertThat(row.get("ORIGIN_IATA").toString()).startsWith("enc:v1:");
        assertThat(row.get("DESTINATION_IATA").toString()).startsWith("enc:v1:");
        assertThat(row.get("STATUS")).isEqualTo("SCHEDULED");   // not sensitive, used in queries

        // the application still sees plain values, and equality queries on encrypted columns still work
        assertThat(repository.findById(flight.flightNumber()).orElseThrow().getAircraftRegistration()).isEqualTo("CS-TPC");
        assertThat(repository.findByAircraftRegistration("CS-TPC"))
                .extracting(f -> f.getFlightNumber()).contains(flight.flightNumber());
        assertThat(repository.findUpcomingDepartures("OPO", departure.minusMinutes(1), departure.plusMinutes(1)))
                .extracting(f -> f.getFlightNumber()).containsExactly(flight.flightNumber());

        // nor is the registration stored in plain text by the per-aircraft booking lock
        assertThat(jdbc.queryForList("SELECT aircraft_registration FROM aircraft_booking_lock", String.class))
                .allMatch(key -> key.startsWith("enc:v1:"));
    }

    @Test
    void rowsStoredBeforeEncryptionAreEncryptedByTheMigration() throws Exception {
        jdbc.update("INSERT INTO scheduled_flight (flight_number, route_id, aircraft_registration, aircraft_model, " +
                "origin_iata, destination_iata, distance_km, fuel_burn_rate, scheduled_departure, scheduled_arrival, " +
                "status, version) VALUES ('legacy-1', 'route-opo-lis', 'CS-OLD', 'A320neo', 'OPO', 'LIS', 277, 3.8, " +
                "?, ?, 'SCHEDULED', 0)", LocalDateTime.now().plusDays(3000), LocalDateTime.now().plusDays(3000).plusMinutes(45));

        migration.run(null);

        String stored = jdbc.queryForObject(
                "SELECT aircraft_registration FROM scheduled_flight WHERE flight_number = 'legacy-1'", String.class);
        assertThat(stored).startsWith("enc:v1:");
        assertThat(repository.findById("legacy-1").orElseThrow().getAircraftRegistration()).isEqualTo("CS-OLD");
    }
}
