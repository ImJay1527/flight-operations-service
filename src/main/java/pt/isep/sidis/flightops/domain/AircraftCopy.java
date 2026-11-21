package pt.isep.sidis.flightops.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * This instance's copy of an aircraft of the Aircraft &amp; Maintenance service: what a booking needs (status, model,
 * range, fuel, seats). Events: aircraft.created, aircraft.status.changed. See {@link ReferenceCopy}.
 */
@Entity
@Table(name = "aircraft_copy")
public class AircraftCopy extends ReferenceCopy {

    @Id
    private String registration;
    private String status;
    private String modelName;
    private double maxRange;
    private double fuelCapacity;
    private int activeCapacity;

    protected AircraftCopy() {
    }

    public AircraftCopy(String registration) {
        this.registration = registration.toUpperCase();
    }

    /** The whole aircraft (aircraft.created, or a fresh read of the REST API); false if it is older than the copy. */
    public boolean replace(String status, String modelName, double maxRange, double fuelCapacity, int activeCapacity,
                           Instant at, String from) {
        if (!accept(at, from)) {
            return false;
        }
        this.status = status;
        this.modelName = modelName;
        this.maxRange = maxRange;
        this.fuelCapacity = fuelCapacity;
        this.activeCapacity = activeCapacity;
        return true;
    }

    /** aircraft.status.changed; false if it is older than the copy. */
    public boolean changeStatus(String newStatus, Instant at) {
        if (!accept(at, "event")) {
            return false;
        }
        this.status = newStatus;
        return true;
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
}
