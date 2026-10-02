package pt.isep.sidis.flightops.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Circuit breaker of one remote instance (a peer, or an instance of another service). PL3 p.12, p.14.
 *
 * <pre>
 *   CLOSED ──(failureThreshold failures in a row)──► OPEN ──(openDuration passed)──► HALF_OPEN
 *     ▲                                                ▲                                 │
 *     └────────────(any success, or a successful health check)──────────────────────────┤
 *                                                      └──(trial request fails: open again, twice as long)
 * </pre>
 *
 * CLOSED: requests go through. OPEN: the instance is skipped without trying (fail fast, and a failing instance is not
 * flooded with requests). HALF_OPEN: one trial request is allowed to see whether the instance is back.
 * {@link HealthChecker} also closes the circuit as soon as the instance's health endpoint answers again.
 */
public class EndpointHealth {

    private static final Logger log = LoggerFactory.getLogger(EndpointHealth.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String group;
    private final String url;
    private final int failureThreshold;
    private final Duration baseOpenDuration;
    private final Duration maxOpenDuration;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Duration openDuration;
    private Instant openUntil;
    private Instant lastSuccess;
    private Instant lastFailure;
    private String lastError;

    public EndpointHealth(String group, String url, int failureThreshold,
                          Duration baseOpenDuration, Duration maxOpenDuration, Clock clock) {
        this.group = group;
        this.url = url;
        this.failureThreshold = failureThreshold;
        this.baseOpenDuration = baseOpenDuration;
        this.maxOpenDuration = maxOpenDuration;
        this.openDuration = baseOpenDuration;
        this.clock = clock;
    }

    public String url() {
        return url;
    }

    public String group() {
        return group;
    }

    /** May a request be sent now? Moves OPEN to HALF_OPEN once the open period is over. */
    public synchronized boolean allowRequest() {
        if (state == State.OPEN) {
            if (clock.instant().isBefore(openUntil)) {
                return false;
            }
            state = State.HALF_OPEN;
            log.info("{} {}: circuit HALF_OPEN, letting a trial request through", group, url);
        }
        return true;
    }

    public synchronized void recordSuccess() {
        boolean wasDown = state != State.CLOSED;
        state = State.CLOSED;
        consecutiveFailures = 0;
        openDuration = baseOpenDuration;
        openUntil = null;
        lastSuccess = clock.instant();
        if (wasDown) {
            log.info("{} {} is UP again: circuit CLOSED", group, url);
        }
    }

    public synchronized void recordFailure(String error) {
        consecutiveFailures++;
        lastFailure = clock.instant();
        lastError = error;
        if (state == State.HALF_OPEN) {
            // the trial failed: stay away twice as long (exponential, capped)
            Duration doubled = openDuration.multipliedBy(2);
            openDuration = doubled.compareTo(maxOpenDuration) > 0 ? maxOpenDuration : doubled;
            open();
        } else if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    private void open() {
        state = State.OPEN;
        openUntil = clock.instant().plus(openDuration);
        log.warn("{} {} is DOWN ({} failures in a row, last: {}): circuit OPEN for {} s",
                group, url, consecutiveFailures, lastError, openDuration.toSeconds());
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(group, url, state, state == State.CLOSED, consecutiveFailures,
                openUntil, lastSuccess, lastFailure, lastError);
    }

    public record Snapshot(String group, String url, State circuit, boolean healthy, int consecutiveFailures,
                           Instant openUntil, Instant lastSuccess, Instant lastFailure, String lastError) {
    }
}
