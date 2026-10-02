package pt.isep.sidis.flightops.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import pt.isep.sidis.flightops.monitoring.FlightOpsMetrics;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Every call to a remote instance (PL3 p.14):
 * <ul>
 *   <li>circuit breaker: an instance whose circuit is open is not called at all;</li>
 *   <li>retry with exponential backoff (100 ms, 200 ms, ... plus jitter) for temporary failures - only for idempotent
 *       requests, since a POST/PATCH may already have worked; it stops as soon as the circuit opens;</li>
 *   <li>a 4xx or 501 is not a failure: the instance is alive, and asking again would get the same answer.</li>
 * </ul>
 */
@Component
public class ResilientCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilientCaller.class);

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final double multiplier;
    private final FlightOpsMetrics metrics;

    public ResilientCaller(@Value("${sidis.resilience.retry.max-attempts:3}") int maxAttempts,
                           @Value("${sidis.resilience.retry.initial-backoff-ms:100}") long initialBackoffMs,
                           @Value("${sidis.resilience.retry.multiplier:2.0}") double multiplier,
                           FlightOpsMetrics metrics) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = initialBackoffMs;
        this.multiplier = multiplier;
        this.metrics = metrics;
    }

    /** {@link #attempt}, recorded in the metrics (PL3 p.19). */
    public <T> T call(EndpointHealth endpoint, boolean idempotent, Supplier<T> request) {
        long start = System.nanoTime();
        try {
            T result = attempt(endpoint, idempotent, request);
            metrics.recordRemoteCall(endpoint, "success", start);
            return result;
        } catch (CircuitOpenException e) {
            metrics.recordRemoteCall(endpoint, "circuit_open", start);
            throw e;
        } catch (HttpClientErrorException e) {
            metrics.recordRemoteCall(endpoint, e.getStatusCode().value() == 404 ? "not_found" : "client_error", start);
            throw e;
        } catch (HttpServerErrorException.NotImplemented e) {
            metrics.recordRemoteCall(endpoint, "not_implemented", start);
            throw e;
        } catch (RuntimeException e) {
            metrics.recordRemoteCall(endpoint, "failure", start);
            throw e;
        }
    }

    private <T> T attempt(EndpointHealth endpoint, boolean idempotent, Supplier<T> request) {
        if (!endpoint.allowRequest()) {
            throw new CircuitOpenException(endpoint.url());
        }
        int attempts = idempotent ? maxAttempts : 1;
        long backoffMs = initialBackoffMs;
        for (int attempt = 1; ; attempt++) {
            try {
                T result = request.get();
                endpoint.recordSuccess();
                return result;
            } catch (HttpClientErrorException | HttpServerErrorException.NotImplemented e) {
                // it answered: alive, and repeating won't change the answer
                endpoint.recordSuccess();
                throw e;
            } catch (RestClientException e) {
                endpoint.recordFailure(e.getMessage());
                if (attempt >= attempts || !endpoint.allowRequest()) {
                    throw e;
                }
                long wait = backoffMs + ThreadLocalRandom.current().nextLong(backoffMs / 2 + 1);
                log.debug("{} failed (attempt {}/{}), retrying in {} ms: {}", endpoint.url(), attempt, attempts, wait, e.getMessage());
                sleep(wait);
                backoffMs = (long) (backoffMs * multiplier);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestClientException("interrupted while waiting to retry", e);
        }
    }
}
