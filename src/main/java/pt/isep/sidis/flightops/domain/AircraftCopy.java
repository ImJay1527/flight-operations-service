package pt.isep.sidis.flightops.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;

/**
 * This instance's copy of an aircraft of the Aircraft &amp; Maintenance service: what a booking needs (status, model,
 * range, fuel, seats). Kept up to date by that service's events (aircraft.created, aircraft.status.changed) and
 * filled from its REST API the first time an aircraft is asked for.
 *
 * <p>{@code asOf} is when the data was true: the event's {@code occurredAt}, or the moment it was read from the REST
 * API. A change older than that is ignored, so a duplicate or an event that arrives late changes nothing.
 * {@code updatedAt} is when this instance last changed the copy (its own clock): the copy's age.
 */
@Entity
@Table(name = "aircraft_copy")
public class AircraftCopy {

    @Id
    private String registration;
    private String status;
    private String modelName;
    private double maxRange;
    private double fuelCapacity;
    private int activeCapacity;
    private Instant asOf;
    private Instant updatedAt;
    private String source;      // event | rest

    @Version
    private long version;

    protected AircraftCopy() {
    }

    public AircraftCopy(String registration) {
        this.registration = registration.toUpperCase();
    }

    /** The whole aircraft (aircraft.created, or a fresh read of the REST API); false if it is older than the copy. */
    public boolean replace(String status, String modelName, double maxRange, double fuelCapacity, int activeCapacity,
                           Instant at, String from) {
        if (asOf != null && at.isBefore(asOf)) {
            return false;
        }
        this.status = status;
        this.modelName = modelName;
        this.maxRange = maxRange;
        this.fuelCapacity = fuelCapacity;
        this.activeCapacity = activeCapacity;
        this.asOf = at;
        this.source = from;
        this.updatedAt = Instant.now();
        return true;
    }

    /** aircraft.status.changed; false if it is older than the copy. */
    public boolean changeStatus(String newStatus, Instant at) {
        if (at.isBefore(asOf)) {
            return false;
        }
        this.status = newStatus;
        this.asOf = at;
        this.source = "event";
        this.updatedAt = Instant.now();
        return true;
    }

    public boolean olderThan(Duration maxAge, Instant now) {
        return updatedAt.plus(maxAge).isBefore(now);
    }

    public String getRegistration() {
        return registration;
    }

    public String getStatus() {
        return status;
    }

    public String getModelName() {
        return modelName;
    }

    public double getMaxRange() {
        return maxRange;
    }

    public double getFuelCapacity() {
        return fuelCapacity;
    }

    public int getActiveCapacity() {
        return activeCapacity;
    }

    public Instant getAsOf() {
        return asOf;
    }

    public String getSource() {
        return source;
    }
}
