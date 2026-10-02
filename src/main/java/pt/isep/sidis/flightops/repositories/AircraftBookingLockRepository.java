package pt.isep.sidis.flightops.repositories;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import pt.isep.sidis.flightops.domain.AircraftBookingLock;

import java.util.Optional;

public interface AircraftBookingLockRepository extends JpaRepository<AircraftBookingLock, String> {

    /** SELECT ... FOR UPDATE on the aircraft's row; waits (max 10 s) while another booking holds it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT l FROM AircraftBookingLock l WHERE l.aircraftRegistration = :registration")
    Optional<AircraftBookingLock> lockForUpdate(@Param("registration") String registration);
}
