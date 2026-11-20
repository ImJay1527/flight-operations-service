package pt.isep.sidis.flightops.readmodel;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Collection {@code aircraft_stats}: one document per aircraft, with its reports already computed (utilization by
 * month, fuel). Recomputed from the {@code flights} collection whenever one of the aircraft's flights changes - not
 * incremented - so applying the same change twice can't count it twice.
 *
 * @param registration encrypted, as in {@code flights}
 * @param flights      non-cancelled flights (the ones the reports count)
 */
@Document(MongoReadModel.AIRCRAFT)
public record AircraftStatsDocument(
        @Id String registration,
        String model,
        double fuelBurnRate,
        int flights,
        int cancelledFlights,
        double totalHours,
        double totalDistanceKm,
        double totalFuelL,
        double kmPerL,
        List<Period> periods,
        Instant updatedAt) {

    public record Period(int year, int month, long flights, double hours) {
    }
}
