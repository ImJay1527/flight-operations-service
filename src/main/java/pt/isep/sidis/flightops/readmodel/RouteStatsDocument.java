package pt.isep.sidis.flightops.readmodel;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Collection {@code route_stats}: one document per route that has non-cancelled flights, with its fuel report
 * already computed. Recomputed from {@code flights} when one of its flights changes.
 *
 * @param routeId encrypted, as in {@code flights}
 */
@Document(MongoReadModel.ROUTES)
public record RouteStatsDocument(
        @Id String routeId,
        String origin,
        String destination,
        double distanceKm,
        int flights,
        double fuelPerFlightL,
        double kmPerL,
        Instant updatedAt) {
}
