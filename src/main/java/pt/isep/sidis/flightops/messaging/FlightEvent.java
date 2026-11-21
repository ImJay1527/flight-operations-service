package pt.isep.sidis.flightops.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.domain.FlightStatus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A flight event on the broker (exchange {@code aisafe.events}, docs/service-contracts.md "Events"): the same fields
 * as Aircraft & Maintenance's FlightCompletedEvent ({@code eventId}, {@code eventType}, {@code occurredAt},
 * {@code flightNumber}, {@code registration}, the airports, {@code durationHours}), plus the whole flight, so any
 * instance can update its read model from the event alone.
 *
 * <p>{@code eventId} is derived from the change (flight, revision, status): the same change always has the same id,
 * so a consumer can drop a duplicate (delivery is at least once).
 *
 * @param durationHours only in flight.completed (scheduled departure to arrival); absent in the other events
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String flightNumber,
        String registration,
        String aircraftModel,
        String routeId,
        String originAirportIata,
        String destinationAirportIata,
        double distanceKm,
        double fuelBurnRate,
        LocalDateTime scheduledDeparture,
        LocalDateTime scheduledArrival,
        String status,
        long revision,
        Double durationHours) {

    public static final String SCHEDULED = "flight.scheduled";
    public static final String CANCELLED = "flight.cancelled";
    public static final String COMPLETED = "flight.completed";

    public static FlightEvent of(FlightView f, Instant occurredAt) {
        FlightStatus status = FlightStatus.valueOf(f.status());
        Double duration = status == FlightStatus.COMPLETED
                ? Duration.between(f.scheduledDeparture(), f.scheduledArrival()).toMinutes() / 60.0
                : null;
        String id = UUID.nameUUIDFromBytes(("flight:" + f.flightNumber() + ":" + f.revision() + ":" + f.status())
                .getBytes(StandardCharsets.UTF_8)).toString();
        return new FlightEvent(id, eventType(status), occurredAt, f.flightNumber(), f.aircraftRegistration(),
                f.aircraftModel(), f.routeId(), f.originIata(), f.destinationIata(), f.distanceKm(), f.fuelBurnRate(),
                f.scheduledDeparture(), f.scheduledArrival(), f.status(), f.revision(), duration);
    }

    public static String routingKey(FlightStatus status) {
        return switch (status) {
            case SCHEDULED -> SCHEDULED;
            case CANCELED -> CANCELLED;
            case COMPLETED -> COMPLETED;
        };
    }

    private static String eventType(FlightStatus status) {
        return switch (status) {
            case SCHEDULED -> "FlightScheduledEvent";
            case CANCELED -> "FlightCancelledEvent";
            case COMPLETED -> "FlightCompletedEvent";
        };
    }

    public String routingKey() {
        return routingKey(FlightStatus.valueOf(status));
    }

    /** The flight as the read model takes it. */
    public FlightView flight() {
        return new FlightView(flightNumber, routeId, registration, aircraftModel, originAirportIata,
                destinationAirportIata, distanceKm, fuelBurnRate, scheduledDeparture, scheduledArrival, status,
                revision);
    }

    /** False if a field the read model needs is missing (a malformed or foreign message). */
    public boolean isComplete() {
        if (flightNumber == null || registration == null || routeId == null || originAirportIata == null
                || destinationAirportIata == null || scheduledDeparture == null || scheduledArrival == null
                || status == null || revision < 1) {
            return false;
        }
        try {
            FlightStatus.valueOf(status);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
