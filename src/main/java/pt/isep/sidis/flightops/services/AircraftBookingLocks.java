package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.sidis.flightops.common.crypto.FieldEncryption;
import pt.isep.sidis.flightops.repositories.AircraftBookingLockRepository;

/**
 * Serialises bookings of the same aircraft on this instance (docs/architecture.md, "Consistency model").
 *
 * <p>{@link #lock} must be called inside the booking's transaction: the row lock is held until that transaction
 * commits or rolls back, so a second booking for the same aircraft waits and then sees the first one's flight.
 */
@Service
@RequiredArgsConstructor
public class AircraftBookingLocks {

    private final AircraftBookingLockRepository repository;
    private final AircraftLockRowCreator lockRowCreator;
    private final FieldEncryption encryption;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String registration) {
        // the lock row is keyed by the encrypted registration (deterministic), so it isn't stored in plain text either
        String aircraftRegistration = encryption.encrypt(registration);
        if (!repository.existsById(aircraftRegistration)) {
            try {
                lockRowCreator.create(aircraftRegistration);   // own transaction, committed right away
            } catch (DataIntegrityViolationException alreadyCreated) {
                // two first bookings of this aircraft raced to create the row: it exists now, that's all we need
            }
        }
        repository.lockForUpdate(aircraftRegistration)
                .orElseThrow(() -> new IllegalStateException("Could not lock aircraft " + registration));
    }
}
