package pt.isep.sidis.flightops.common.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Encrypts, at startup, rows stored before encryption at rest existed. Runs before the sample-data bootstrap. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EncryptionMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EncryptionMigration.class);
    private static final List<String> COLUMNS =
            List.of("route_id", "aircraft_registration", "aircraft_model", "origin_iata", "destination_iata");

    private final JdbcTemplate jdbc;
    private final FieldEncryption encryption;

    public EncryptionMigration(JdbcTemplate jdbc, FieldEncryption encryption) {
        this.jdbc = jdbc;
        this.encryption = encryption;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> plain = jdbc.queryForList(
                "SELECT flight_number, " + String.join(", ", COLUMNS)
                        + " FROM scheduled_flight WHERE aircraft_registration NOT LIKE ?", FieldEncryption.PREFIX + "%");
        for (Map<String, Object> row : plain) {
            Object[] values = new Object[COLUMNS.size() + 1];
            for (int i = 0; i < COLUMNS.size(); i++) {
                values[i] = encryption.encrypt((String) row.get(COLUMNS.get(i)));
            }
            values[COLUMNS.size()] = row.get("flight_number");
            jdbc.update("UPDATE scheduled_flight SET " + String.join(" = ?, ", COLUMNS) + " = ? WHERE flight_number = ?",
                    values);
        }
        // lock rows are keyed by the encrypted registration now; plaintext keys are obsolete
        int oldLocks = jdbc.update("DELETE FROM aircraft_booking_lock WHERE aircraft_registration NOT LIKE ?",
                FieldEncryption.PREFIX + "%");
        if (!plain.isEmpty() || oldLocks > 0) {
            log.info("Encryption at rest: encrypted {} existing flight(s), removed {} old lock row(s)", plain.size(), oldLocks);
        }
    }
}
