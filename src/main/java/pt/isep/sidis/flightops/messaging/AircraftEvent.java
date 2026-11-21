package pt.isep.sidis.flightops.messaging;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import pt.isep.sidis.flightops.clients.AircraftInfo;

import java.time.Instant;

/**
 * An event of the Aircraft &amp; Maintenance service (docs/service-contracts.md "Events"). Only the fields this
 * service uses; the others are ignored.
 *
 * <p>The model: {@code modelCode} is the model's name, the same value as {@code modelName} of
 * GET /internal/aircraft/{registration} (what the airports are certified for). {@code modelName} is used only when
 * {@code modelCode} is missing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AircraftEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String registration,
        String modelCode,
        String modelName,
        Double maxRange,
        Double fuelCapacity,
        @JsonAlias("activeCapacity") Integer seatingCapacity,
        String status,
        String newStatus) {

    public static final String CREATED = "aircraft.created";
    public static final String STATUS_CHANGED = "aircraft.status.changed";

    /** aircraft.created: everything a booking needs. */
    public boolean isCompleteAircraft() {
        return hasText(registration) && (hasText(modelCode) || hasText(modelName))
                && maxRange != null && maxRange > 0 && fuelCapacity != null && fuelCapacity > 0;
    }

    /** aircraft.status.changed */
    public boolean isCompleteStatusChange() {
        return hasText(registration) && hasText(newStatus);
    }

    public AircraftInfo aircraft() {
        return new AircraftInfo(registration.toUpperCase(), hasText(status) ? status : "AVAILABLE",
                hasText(modelCode) ? modelCode : modelName, maxRange, fuelCapacity,
                seatingCapacity == null ? 0 : seatingCapacity);
    }

    /** When it happened; now if the event doesn't say. */
    public Instant at() {
        return occurredAt != null ? occurredAt : Instant.now();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
