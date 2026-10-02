package pt.isep.sidis.flightops.domain;

import jakarta.persistence.*;
import lombok.Getter;
import pt.isep.sidis.flightops.common.crypto.EncryptedString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A scheduled flight. In the monolith this entity pointed (JPA @ManyToOne) at FlightRoute and Aircraft;
 * those now live in other services, so we keep only their identifiers plus a snapshot of the few
 * values this service needs for its own queries (utilization, fuel efficiency, departures board).
 *
 * <p>The route assignment (route, aircraft, model, airports) is encrypted in the database (P1 p.16), see
 * {@link pt.isep.sidis.flightops.common.crypto.FieldEncryption}; times, status and numbers stay readable because
 * they are used in range queries.
 */
@Entity
@Getter
@Table(name = "scheduled_flight")
public class ScheduledFlight {

    @Id
    private String flightNumber;

    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)   // encrypted at rest
    private String routeId;

    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)   // encrypted at rest
    private String aircraftRegistration;

    // ---- snapshot of remote data, captured when the flight is scheduled ----
    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)   // encrypted at rest
    private String aircraftModel;

    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)   // encrypted at rest
    private String originIata;

    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)   // encrypted at rest
    private String destinationIata;

    @Column(nullable = false)
    private double distanceKm;

    /** fuelCapacity / maxRange of the aircraft model. */
    @Column(nullable = false)
    private double fuelBurnRate;
    // -------------------------------------------------------------------------

    @Column(nullable = false)
    private LocalDateTime scheduledDeparture;

    @Column(nullable = false)
    private LocalDateTime scheduledArrival;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlightStatus status;

    @Version
    private Long version;

    protected ScheduledFlight() {
    }

    public ScheduledFlight(String routeId, String aircraftRegistration, String aircraftModel,
                           String originIata, String destinationIata, double distanceKm, double fuelBurnRate,
                           LocalDateTime scheduledDeparture, LocalDateTime scheduledArrival) {
        if (routeId == null || routeId.isBlank()) throw new IllegalArgumentException("Flight route cannot be null.");
        if (aircraftRegistration == null || aircraftRegistration.isBlank()) throw new IllegalArgumentException("Aircraft cannot be null.");
        if (scheduledDeparture == null || scheduledArrival == null) throw new IllegalArgumentException("Departure and arrival times must be provided.");
        if (!scheduledArrival.isAfter(scheduledDeparture)) throw new IllegalArgumentException("Arrival time must be after departure time.");

        this.flightNumber = UUID.randomUUID().toString();
        this.routeId = routeId;
        this.aircraftRegistration = aircraftRegistration;
        this.aircraftModel = aircraftModel;
        this.originIata = originIata;
        this.destinationIata = destinationIata;
        this.distanceKm = distanceKm;
        this.fuelBurnRate = fuelBurnRate;
        this.scheduledDeparture = scheduledDeparture;
        this.scheduledArrival = scheduledArrival;
        this.status = FlightStatus.SCHEDULED;
    }

    public void cancel() {
        if (this.status == FlightStatus.CANCELED) {
            throw new IllegalStateException("This flight is already canceled.");
        }
        if (this.status == FlightStatus.COMPLETED) {
            throw new IllegalStateException("Cannot cancel a completed flight.");
        }
        this.status = FlightStatus.CANCELED;
    }
}
