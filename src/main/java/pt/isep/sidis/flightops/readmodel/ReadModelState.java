package pt.isep.sidis.flightops.readmodel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether the read model can be trusted, and since when. Shown in the X-Read-Model header and on the dashboard.
 * <ul>
 *   <li>DISABLED - no read database configured (profile without "cqrs"): queries always take the fallback path;</li>
 *   <li>BUILDING - starting, the first sync has not finished;</li>
 *   <li>READY - complete: queries are answered from it;</li>
 *   <li>STALE - may be missing changes (read database down, or too many instances unreachable during the last
 *       sync): queries take the fallback path until a sync succeeds.</li>
 * </ul>
 */
public class ReadModelState {

    private static final Logger log = LoggerFactory.getLogger(ReadModelState.class);

    public enum State { DISABLED, BUILDING, READY, STALE }

    /** @param reason why it is not READY (null when READY) */
    public record Status(State state, String reason, Instant since) {
    }

    private final AtomicReference<Status> current;
    private volatile Instant lastProjection;
    private volatile Instant lastSync;

    public ReadModelState(State initial) {
        current = new AtomicReference<>(new Status(initial, null, Instant.now()));
    }

    public Status current() {
        return current.get();
    }

    /** For the monitoring API and the dashboard. */
    public record Snapshot(State state, String reason, Instant since, Instant lastProjection, Instant lastSync) {
    }

    public Snapshot snapshot() {
        Status s = current.get();
        return new Snapshot(s.state(), s.reason(), s.since(), lastProjection, lastSync);
    }

    public Instant lastProjection() {
        return lastProjection;
    }

    public Instant lastSync() {
        return lastSync;
    }

    /** The last time anything was written to the read model (a projection or a sync); null if never. */
    public Instant lastUpdate() {
        Instant p = lastProjection;
        Instant s = lastSync;
        return p == null ? s : s == null || p.isAfter(s) ? p : s;
    }

    void projected() {
        lastProjection = Instant.now();
    }

    void synced() {
        lastSync = Instant.now();
    }

    void ready() {
        Status before = current.getAndUpdate(s -> s.state() == State.READY ? s : new Status(State.READY, null, Instant.now()));
        if (before.state() != State.READY) {
            log.info("Read model READY (was {}{})", before.state(), before.reason() == null ? "" : ": " + before.reason());
        }
    }

    void stale(String reason) {
        Status before = current.getAndUpdate(s -> new Status(State.STALE, reason,
                s.state() == State.STALE ? s.since() : Instant.now()));
        if (before.state() != State.STALE) {
            log.warn("Read model STALE: {} - queries use the write side until the next successful sync", reason);
        }
    }
}
