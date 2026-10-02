package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.sidis.flightops.common.crypto.FieldEncryption;
import pt.isep.sidis.flightops.repositories.AircraftBookingLockRepository;

/**
 * Serialises bookings of the same aircraft. {@link #lock} must be called inside the booking's transaction: the row
 * lock is held until it ends, so a second booking for the same aircraft waits and then sees the first one's flight.
 * (Locking the overlapping flights is not enough: when there are none yet, there is nothing to lock.)
 */
@Service
@RequiredArgsConstructor
public class AircraftBookingLocks {

    private final AircraftBookingLockRepository repository;
    private final AircraftLockRowCreator lockRowCreator;
    private final FieldEncryption encryption;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String registration) {
        // keyed by the encrypted registration, so it isn't stored in plain text
        String aircraftRegistration = encryption.encrypt(registration);
        if (!repository.existsById(aircraftRegistration)) {
            try {
                lockRowCreator.create(aircraftRegistration);
            } catch (DataIntegrityViolationException alreadyCreated) {
                // another booking created it at the same moment: fine
            }
        }
        repository.lockForUpdate(aircraftRegistration)
                .orElseThrow(() -> new IllegalStateException("Could not lock aircraft " + registration));
    }
}
