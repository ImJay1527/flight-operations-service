package pt.isep.sidis.flightops.domain;

import jakarta.persistence.*;
import lombok.Getter;
import pt.isep.sidis.flightops.common.crypto.EncryptedString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Route and aircraft live in other services, so a flight keeps their ids plus a snapshot of the values this service
 * needs for its own queries. The route assignment is encrypted at rest (P1 p.16); times, status and numbers stay
 * readable because they are used in range queries.
 */
@Entity
@Getter
@Table(name = "scheduled_flight")
public class ScheduledFlight {

    @Id
    private String flightNumber;

    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)
    private String routeId;

    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)
    private String aircraftRegistration;

    // snapshot of the other services' data, taken when the flight is scheduled
    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)
    private String aircraftModel;

    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)
    private String originIata;

    @Column(nullable = false)
    @Convert(converter = EncryptedString.class)
    private String destinationIata;

    @Column(nullable = false)
    private double distanceKm;

    /** fuelCapacity / maxRange of the aircraft model. */
    @Column(nullable = false)
    private double fuelBurnRate;

    @Column(nullable = false)
    private LocalDateTime scheduledDeparture;

    @Column(nullable = false)
    private LocalDateTime scheduledArrival;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlightStatus status;

    @Version
    private Long version;

    /**
     * Grows with every change, on whichever instance makes it. Copies of a flight on different instances are
     * reconciled by keeping the one with the highest revision.
     */
    @Column(nullable = false, columnDefinition = "bigint default 1")
    private long revision = 1;

    protected ScheduledFlight() {
    }

    public ScheduledFlight(String routeId, String aircraftRegistration, String aircraftModel,
                           String originIata, String destinationIata, double distanceKm, double fuelBurnRate,
                           LocalDateTime scheduledDeparture, LocalDateTime scheduledArrival) {
        this(UUID.randomUUID().toString(), routeId, aircraftRegistration, aircraftModel, originIata, destinationIata,
                distanceKm, fuelBurnRate, scheduledDeparture, scheduledArrival);
    }

    /** With a given flight number: sample data gets the same number on every instance that holds it. */
    public ScheduledFlight(String flightNumber, String routeId, String aircraftRegistration, String aircraftModel,
                           String originIata, String destinationIata, double distanceKm, double fuelBurnRate,
                           LocalDateTime scheduledDeparture, LocalDateTime scheduledArrival) {
        if (routeId == null || routeId.isBlank()) throw new IllegalArgumentException("Flight route cannot be null.");
        if (aircraftRegistration == null || aircraftRegistration.isBlank()) throw new IllegalArgumentException("Aircraft cannot be null.");
        if (scheduledDeparture == null || scheduledArrival == null) throw new IllegalArgumentException("Departure and arrival times must be provided.");
        if (!scheduledArrival.isAfter(scheduledDeparture)) throw new IllegalArgumentException("Arrival time must be after departure time.");

        this.flightNumber = flightNumber;
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
        this.revision++;
    }

    /** Sets the state of a copy received from another instance, before it is first saved here. */
    public void restoreCopyState(FlightStatus status, long revision) {
        this.status = status;
        this.revision = revision;
    }

    /**
     * Takes over the state of another instance's copy if that one is newer: higher revision, or on a tie a later
     * status (a flight only ever goes from SCHEDULED to CANCELED or COMPLETED). Every instance applies the same rule,
     * so all copies end up equal whatever order the updates arrive in. False if nothing changed.
     */
    public boolean updateFromCopy(FlightStatus otherStatus, long otherRevision) {
        boolean newer = otherRevision > revision
                || (otherRevision == revision && otherStatus.ordinal() > status.ordinal());
        if (!newer) {
            return false;
        }
        this.status = otherStatus;
        this.revision = otherRevision;
        return true;
    }
}
