package pt.isep.sidis.flightops.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointHealthTest {

    /** A clock the test can move forward. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-02T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private TestClock clock;
    private EndpointHealth peer;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        peer = new EndpointHealth("peer", "http://peer", 3, Duration.ofSeconds(10), Duration.ofSeconds(60), clock);
    }

    @Test
    void staysClosedBelowThreshold() {
        peer.recordFailure("x");
        peer.recordFailure("x");
        assertThat(peer.allowRequest()).isTrue();
        assertThat(peer.snapshot().circuit()).isEqualTo(EndpointHealth.State.CLOSED);
    }

    @Test
    void successResetsTheFailureCount() {
        peer.recordFailure("x");
        peer.recordFailure("x");
        peer.recordSuccess();
        peer.recordFailure("x");
        assertThat(peer.snapshot().circuit()).isEqualTo(EndpointHealth.State.CLOSED);
    }

    @Test
    void opensAfterThresholdAndBlocksRequests() {
        failTimes(3);
        assertThat(peer.snapshot().circuit()).isEqualTo(EndpointHealth.State.OPEN);
        assertThat(peer.allowRequest()).isFalse();
        assertThat(peer.snapshot().healthy()).isFalse();
    }

    @Test
    void afterOpenPeriodLetsOneTrialThrough() {
        failTimes(3);
        clock.advance(Duration.ofSeconds(10));
        assertThat(peer.allowRequest()).isTrue();
        assertThat(peer.snapshot().circuit()).isEqualTo(EndpointHealth.State.HALF_OPEN);
    }

    @Test
    void failedTrialOpensAgainForTwiceAsLong() {
        failTimes(3);
        clock.advance(Duration.ofSeconds(10));
        peer.allowRequest();               // HALF_OPEN
        peer.recordFailure("still down");

        clock.advance(Duration.ofSeconds(19));
        assertThat(peer.allowRequest()).isFalse();   // 20 s now, not 10 s
        clock.advance(Duration.ofSeconds(1));
        assertThat(peer.allowRequest()).isTrue();
    }

    @Test
    void openDurationIsCappedAtMax() {
        failTimes(3);
        for (int i = 0; i < 10; i++) {               // keep failing the trials: 20, 40, 60, 60, ...
            clock.advance(Duration.ofSeconds(60));
            peer.allowRequest();
            peer.recordFailure("down");
        }
        clock.advance(Duration.ofSeconds(60));
        assertThat(peer.allowRequest()).isTrue();
    }

    @Test
    void successfulTrialOrHealthCheckClosesTheCircuit() {
        failTimes(3);
        peer.recordSuccess();   // e.g. HealthChecker saw /actuator/health answer
        assertThat(peer.snapshot().circuit()).isEqualTo(EndpointHealth.State.CLOSED);
        assertThat(peer.allowRequest()).isTrue();
    }

    private void failTimes(int n) {
        for (int i = 0; i < n; i++) {
            peer.recordFailure("connection refused");
        }
    }
}
