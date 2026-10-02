package pt.isep.sidis.flightops.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per aircraft, used only as a lock: a booking locks its aircraft's row (SELECT ... FOR UPDATE) until its
 * transaction commits, so two bookings for the same aircraft on this instance run one after the other.
 * Locking the overlapping flights is not enough: when there are none yet, there is nothing to lock.
 * See docs/architecture.md, "Consistency model".
 */
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
