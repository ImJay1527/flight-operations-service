package pt.isep.sidis.flightops.domain;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;

/**
 * This instance's copy of data owned by another service (an aircraft, an airport, a route), kept up to date by that
 * service's events and filled from its REST API the first time it is asked for.
 *
 * <p>{@code asOf} is when the data was true: the event's {@code occurredAt}, or the moment it was read from the REST
 * API. A change older than that is ignored, so a duplicate or an event that arrives late changes nothing.
 * {@code updatedAt} is when this instance last changed the copy (its own clock): the copy's age. {@code version}:
 * two writers at once (a booking and an event), one of them fails.
 *
 * <p>Not encrypted at rest (unlike the flights): reference data of the other services, with no flight in it.
 */
@MappedSuperclass
public abstract class ReferenceCopy {

    private Instant asOf;
    private Instant updatedAt;
    private String source;      // event | rest

    @Version
    private long version;

    /** True, and the copy is stamped, if a change of {@code at} is not older than the copy. */
    protected boolean accept(Instant at, String from) {
        if (asOf != null && at.isBefore(asOf)) {
            return false;
        }
        this.asOf = at;
        this.source = from;
        this.updatedAt = Instant.now();
        return true;
    }

    public boolean olderThan(Duration maxAge, Instant now) {
        return updatedAt.plus(maxAge).isBefore(now);
    }

    public Instant getAsOf() {
        return asOf;
    }

    public String getSource() {
        return source;
    }
}
