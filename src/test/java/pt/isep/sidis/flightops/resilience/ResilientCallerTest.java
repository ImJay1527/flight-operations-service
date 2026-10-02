package pt.isep.sidis.flightops.resilience;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import pt.isep.sidis.flightops.monitoring.FlightOpsMetrics;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientCallerTest {

    private EndpointHealth peer;
    private ResilientCaller caller;
    private AtomicInteger calls;

    @BeforeEach
    void setUp() {
        peer = new EndpointHealth("peer", "http://peer", 3, Duration.ofSeconds(10), Duration.ofSeconds(60), Clock.systemUTC());
        caller = new ResilientCaller(3, 1, 2.0, new FlightOpsMetrics(new SimpleMeterRegistry()));   // 1 ms backoff: fast test
        calls = new AtomicInteger();
    }

    @Test
    void retriesTemporaryFailuresAndSucceeds() {
        String result = caller.call(peer, true, () -> {
            if (calls.incrementAndGet() < 3) throw new ResourceAccessException("connection refused");
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(peer.snapshot().circuit()).isEqualTo(EndpointHealth.State.CLOSED);
    }

    @Test
    void givesUpAfterMaxAttemptsAndOpensTheCircuit() {
        assertThatThrownBy(() -> caller.call(peer, true, () -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("connection refused");
        })).isInstanceOf(ResourceAccessException.class);
        assertThat(calls).hasValue(3);
        assertThat(peer.snapshot().circuit()).isEqualTo(EndpointHealth.State.OPEN);
    }

    @Test
    void openCircuitSkipsTheCallEntirely() {
        for (int i = 0; i < 3; i++) peer.recordFailure("down");

        assertThatThrownBy(() -> caller.call(peer, true, () -> calls.incrementAndGet()))
                .isInstanceOf(CircuitOpenException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void nonIdempotentRequestsAreNotRetried() {
        assertThatThrownBy(() -> caller.call(peer, false, () -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("timeout");
        })).isInstanceOf(ResourceAccessException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void clientErrorMeansTheInstanceIsAliveAndIsNotRetried() {
        peer.recordFailure("earlier failure");
        assertThatThrownBy(() -> caller.call(peer, true, () -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "not here", null, null, null);
        })).isInstanceOf(HttpClientErrorException.NotFound.class);
        assertThat(calls).hasValue(1);
        assertThat(peer.snapshot().consecutiveFailures()).isZero();
    }
}
