package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.sidis.flightops.domain.AircraftBookingLock;
import pt.isep.sidis.flightops.repositories.AircraftBookingLockRepository;

/**
 * Creates an aircraft's lock row in its own short transaction (REQUIRES_NEW), so the row exists and is committed
 * before a booking tries to lock it. A separate bean, because Spring only starts the new transaction for calls that
 * go through the bean's proxy.
 */
@Service
@RequiredArgsConstructor
public class AircraftLockRowCreator {

    private final AircraftBookingLockRepository repository;

    /** @throws org.springframework.dao.DataIntegrityViolationException if another booking created it first */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(String aircraftRegistration) {
        repository.saveAndFlush(new AircraftBookingLock(aircraftRegistration));
    }
}
