package pt.isep.sidis.flightops.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row per aircraft, used only as a lock (see {@code AircraftBookingLocks}). */
@Entity
@Table(name = "aircraft_booking_lock")
public class AircraftBookingLock {

    @Id
    private String aircraftRegistration;

    protected AircraftBookingLock() {
    }

    public AircraftBookingLock(String aircraftRegistration) {
        this.aircraftRegistration = aircraftRegistration;
    }

    public String getAircraftRegistration() {
        return aircraftRegistration;
    }
}
