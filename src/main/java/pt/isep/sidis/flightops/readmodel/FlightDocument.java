package pt.isep.sidis.flightops.readmodel;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Collection {@code flights}: one document per flight of the whole cluster, ready to be returned. The sensitive
 * fields are stored encrypted (deterministic, so the indexes on registration and origin still work), as in the
 * write database.
 *
 * @param statusRank the status's position in FlightStatus, the tie-break between two copies with the same revision
 * @param projectedAt when this instance last wrote the document
 */
@Document(MongoReadModel.FLIGHTS)
public record FlightDocument(
        @Id String flightNumber,
        String routeId,
        String registration,
        String model,
        String origin,
        String destination,
        double distanceKm,
        double fuelBurnRate,
        LocalDateTime departure,
        LocalDateTime arrival,
        String status,
        int statusRank,
        long revision,
        Instant projectedAt) {
}
