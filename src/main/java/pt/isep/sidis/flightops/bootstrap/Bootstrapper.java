package pt.isep.sidis.flightops.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.domain.ScheduledFlight;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;
import pt.isep.sidis.flightops.security.SystemUser;
import pt.isep.sidis.flightops.security.SystemUserRepository;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Seeds users and sample flights. Ids must match the other services' bootstrap data (docs/service-contracts.md).
 * Every instance loads the users; each sample flight is loaded by the instances that hold its aircraft
 * ({@link Cluster#replicasOf}), with the same flight number everywhere. Without a cluster list, {@code shard}
 * 1..shard-count picks a slice and 0 loads everything.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)   // before the replication catch-up (ReplicaSync)
public class Bootstrapper implements CommandLineRunner {

    private static final String OPO_LIS = "route-opo-lis";
    private static final String LIS_MAD = "route-lis-mad";
    private static final String MAD_OPO = "route-mad-opo";

    // fuelCapacity / maxRange of the models (A320neo: 24000/6300, 737 MAX: 26000/6500)
    private static final double A320_BURN = 24000.0 / 6300.0;
    private static final double B737_BURN = 26000.0 / 6500.0;

    private final SystemUserRepository userRepository;
    private final ScheduledFlightRepository flightRepository;
    private final PasswordEncoder passwordEncoder;
    private final int shard;
    private final int shardCount;
    private final Cluster cluster;
    private int sampleCount;

    public Bootstrapper(SystemUserRepository userRepository, ScheduledFlightRepository flightRepository,
                        PasswordEncoder passwordEncoder,
                        @Value("${flightops.bootstrap.shard:0}") int shard,
                        @Value("${flightops.bootstrap.shard-count:2}") int shardCount,
                        Cluster cluster) {
        if (shard < 0 || shard > shardCount) {
            throw new IllegalArgumentException("flightops.bootstrap.shard must be between 0 and " + shardCount);
        }
        this.userRepository = userRepository;
        this.flightRepository = flightRepository;
        this.passwordEncoder = passwordEncoder;
        this.shard = shard;
        this.shardCount = shardCount;
        this.cluster = cluster;
    }

    @Override
    @Transactional
    public void run(String... args) {
        bootstrapUsers();
        bootstrapFlights();
    }

    private void bootstrapUsers() {
        if (userRepository.count() > 0) return;
        userRepository.save(new SystemUser("atcc", passwordEncoder.encode("atcc123"), "ATCC"));
        userRepository.save(new SystemUser("operator", passwordEncoder.encode("operator123"), "BACKOFFICE_OPERATOR"));
        userRepository.save(new SystemUser("technician", passwordEncoder.encode("technician123"), "MAINTENANCE_TECHNICIAN"));
        userRepository.save(new SystemUser("supervisor", passwordEncoder.encode("supervisor123"), "MAINTENANCE_SUPERVISOR"));
        userRepository.save(new SystemUser("admin", passwordEncoder.encode("admin123"),
                "ADMIN,BACKOFFICE_OPERATOR,ATCC,MAINTENANCE_TECHNICIAN,MAINTENANCE_SUPERVISOR"));
    }

    private void bootstrapFlights() {
        if (flightRepository.count() > 0) return;
        LocalDateTime now = LocalDateTime.now();

        sampleCount = 0;
        List<ScheduledFlight> sample = List.of(
                flight(OPO_LIS, "CS-TPA", "A320neo", A320_BURN, "OPO", "LIS", 277.0, now.minusDays(90), 45),
                flight(LIS_MAD, "CS-TPB", "737 MAX", B737_BURN, "LIS", "MAD", 502.0, now.minusDays(85), 80),
                flight(MAD_OPO, "CS-TPC", "A320neo", A320_BURN, "MAD", "OPO", 420.0, now.minusDays(80), 70),
                flight(OPO_LIS, "CS-TPB", "737 MAX", B737_BURN, "OPO", "LIS", 277.0, now.minusDays(75), 45),
                flight(LIS_MAD, "CS-TPA", "A320neo", A320_BURN, "LIS", "MAD", 502.0, now.minusDays(70), 80),
                flight(MAD_OPO, "CS-TPA", "A320neo", A320_BURN, "MAD", "OPO", 420.0, now.minusDays(60), 70),
                flight(OPO_LIS, "CS-TPC", "A320neo", A320_BURN, "OPO", "LIS", 277.0, now.minusDays(45), 45),
                flight(LIS_MAD, "CS-TPC", "A320neo", A320_BURN, "LIS", "MAD", 502.0, now.minusDays(30), 80),
                flight(OPO_LIS, "CS-TPA", "A320neo", A320_BURN, "OPO", "LIS", 277.0, now.minusDays(15), 45),
                flight(LIS_MAD, "CS-TPB", "737 MAX", B737_BURN, "LIS", "MAD", 502.0, now.plusDays(5), 80));

        for (int i = 0; i < sample.size(); i++) {
            ScheduledFlight flight = sample.get(i);
            boolean mine = cluster.isClustered()
                    ? cluster.holdsLocally(flight.getAircraftRegistration())
                    : shard == 0 || i % shardCount == shard - 1;
            if (mine) {
                flightRepository.save(flight);
            }
        }
    }

    /** The n-th sample flight gets the same flight number on every instance, so its copies match. */
    private ScheduledFlight flight(String routeId, String registration, String model, double burnRate,
                                   String origin, String destination, double distanceKm,
                                   LocalDateTime departure, int minutes) {
        String flightNumber = UUID.nameUUIDFromBytes(
                ("aisafe-sample-flight-" + sampleCount++).getBytes(StandardCharsets.UTF_8)).toString();
        return new ScheduledFlight(flightNumber, routeId, registration, model, origin, destination,
                distanceKm, burnRate, departure, departure.plusMinutes(minutes));
    }
}
