package pt.isep.sidis.flightops.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import pt.isep.sidis.flightops.common.exceptions.ServiceUnavailableException;
import pt.isep.sidis.flightops.common.metrics.FlightOpsMetrics;
import pt.isep.sidis.flightops.domain.AircraftCopy;
import pt.isep.sidis.flightops.repositories.AircraftCopyRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The aircraft from this instance's own copy (table aircraft_copy) instead of a REST call to the Aircraft &amp;
 * Maintenance service on every booking. With the "amqp" profile only: the copy is kept up to date by that service's
 * events (aircraft.created, aircraft.status.changed), which every instance gets on its own queue.
 *
 * <p>An aircraft not in the copy yet is read from the REST API ({@code remote}) and kept. A copy that hasn't changed
 * for {@code copy-max-age-ms} is read again (events can be lost, e.g. a queue removed after a long stop),
 * and if the REST API doesn't answer the copy is used anyway: a booking doesn't need the other service to be up.
 * The price is eventual consistency: for a few milliseconds after a status change (longer with the broker down) a
 * booking can still see the old status.
 */
@Component
@Primary
@ConditionalOnProperty(name = "flightops.messaging.enabled", havingValue = "true")
public class LocalAircraftCopy implements AircraftDirectory {

    private static final Logger log = LoggerFactory.getLogger(LocalAircraftCopy.class);

    private final AircraftCopyRepository copies;
    private final AircraftDirectory remote;
    private final TransactionTemplate ownTransaction;
    private final FlightOpsMetrics metrics;
    private final Duration maxAge;

    public LocalAircraftCopy(AircraftCopyRepository copies, @Qualifier(AircraftDirectory.REMOTE) AircraftDirectory remote,
                             PlatformTransactionManager transactions, FlightOpsMetrics metrics,
                             @Value("${flightops.messaging.copy-max-age-ms:3600000}") long maxAgeMs) {
        this.copies = copies;
        this.remote = remote;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.metrics = metrics;
        this.maxAge = Duration.ofMillis(maxAgeMs);
    }

    @Override
    public AircraftInfo getAircraft(String registration) {
        Optional<AircraftCopy> copy = copies.findById(registration.toUpperCase());
        if (copy.isPresent() && !copy.get().olderThan(maxAge, Instant.now())) {
            metrics.recordAircraftLookup("copy");
            return info(copy.get());
        }
        AircraftInfo fresh;
        try {
            fresh = remote.getAircraft(registration);   // 404 if that service doesn't know it
        } catch (ServiceUnavailableException e) {
            if (copy.isEmpty()) {
                throw e;
            }
            metrics.recordAircraftLookup("old_copy");
            log.warn("Aircraft {}: {}; using the copy of {}", registration, e.getMessage(), copy.get().getAsOf());
            return info(copy.get());
        }
        metrics.recordAircraftLookup(copy.isPresent() ? "refreshed" : "rest");
        keep(fresh, Instant.now(), "rest");
        return fresh;
    }

    /** aircraft.created: the whole aircraft. False if the copy is newer (a duplicate or a late event). */
    public boolean created(AircraftInfo aircraft, Instant at) {
        return keep(aircraft, at, "event");
    }

    /**
     * aircraft.status.changed. False if the copy is newer, or if the aircraft isn't in the copy: it is read from the
     * REST API (with its current status) the first time a booking asks for it.
     */
    public boolean statusChanged(String registration, String newStatus, Instant at) {
        return Boolean.TRUE.equals(ownTransaction.execute(tx -> {
            Optional<AircraftCopy> copy = copies.findById(registration.toUpperCase());
            if (copy.isEmpty() || !copy.get().changeStatus(newStatus, at)) {
                return false;
            }
            copies.save(copy.get());
            return true;
        }));
    }

    public long size() {
        return copies.count();
    }

    /**
     * In a transaction of its own, so a booking that fails (and rolls back) still keeps what it read. Two writers at
     * once (a booking and an event): the second one fails on the version or the key; an event is then retried by the
     * listener, a REST read is simply not kept.
     */
    private boolean keep(AircraftInfo aircraft, Instant at, String source) {
        try {
            return Boolean.TRUE.equals(ownTransaction.execute(tx -> {
                AircraftCopy copy = copies.findById(aircraft.registrationNumber().toUpperCase())
                        .orElseGet(() -> new AircraftCopy(aircraft.registrationNumber()));
                if (!copy.replace(aircraft.status(), aircraft.modelName(), aircraft.maxRange(),
                        aircraft.fuelCapacity(), aircraft.activeCapacity(), at, source)) {
                    return false;
                }
                copies.save(copy);
                return true;
            }));
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            if ("event".equals(source)) {
                throw e;
            }
            log.debug("Aircraft {} changed while it was read from the REST API: not kept", aircraft.registrationNumber());
            return false;
        }
    }

    private static AircraftInfo info(AircraftCopy c) {
        return new AircraftInfo(c.getRegistration(), c.getStatus(), c.getModelName(), c.getMaxRange(),
                c.getFuelCapacity(), c.getActiveCapacity());
    }
}
