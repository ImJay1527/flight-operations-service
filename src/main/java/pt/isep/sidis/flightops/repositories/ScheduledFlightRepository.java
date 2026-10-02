package pt.isep.sidis.flightops.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pt.isep.sidis.flightops.domain.ScheduledFlight;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduledFlightRepository extends JpaRepository<ScheduledFlight, String> {

    List<ScheduledFlight> findByAircraftRegistration(String aircraftRegistration);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sf FROM ScheduledFlight sf WHERE sf.aircraftRegistration = :registration " +
           "AND sf.status = 'SCHEDULED' " +
           "AND (sf.scheduledDeparture <= :bufferArrival AND sf.scheduledArrival >= :bufferDeparture)")
    List<ScheduledFlight> findOverlappingFlightsWithLock(
            @Param("registration") String registration,
            @Param("bufferDeparture") LocalDateTime bufferDeparture,
            @Param("bufferArrival") LocalDateTime bufferArrival);

    @Query("SELECT sf FROM ScheduledFlight sf " +
           "WHERE sf.originIata = :originIata " +
           "AND sf.status != 'CANCELED' " +
           "AND sf.scheduledDeparture >= :now " +
           "AND sf.scheduledDeparture <= :endWindow " +
           "ORDER BY sf.scheduledDeparture ASC")
    List<ScheduledFlight> findUpcomingDepartures(
            @Param("originIata") String originIata,
            @Param("now") LocalDateTime now,
            @Param("endWindow") LocalDateTime endWindow);

    @Query("SELECT sf FROM ScheduledFlight sf " +
           "WHERE sf.status != 'CANCELED' " +
           "AND (:registration IS NULL OR sf.aircraftRegistration = :registration) " +
           "ORDER BY sf.aircraftRegistration ASC, sf.scheduledDeparture ASC")
    List<ScheduledFlight> findNonCancelledFlights(@Param("registration") String registration);
}
