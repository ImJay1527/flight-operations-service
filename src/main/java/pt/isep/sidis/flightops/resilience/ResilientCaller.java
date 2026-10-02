package pt.isep.sidis.flightops.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Wraps every call to a remote instance (PL3 p.14, "Error Handling and Resilience"):
 * <ul>
 *   <li><b>Circuit breaker</b>: an instance whose circuit is open is not called at all ({@link CircuitOpenException}).</li>
 *   <li><b>Retry with exponential backoff</b> for temporary failures (connection refused, timeout, 5xx):
 *       wait 100 ms, then 200 ms, ... (plus a little random jitter so instances don't retry in lock-step).
 *       Only for idempotent requests (GET); a PATCH is never repeated because the first attempt may have worked.
 *       Retrying stops as soon as the circuit opens, so a failing instance is not flooded.</li>
 *   <li>A 4xx answer (e.g. 404 "not here") is NOT a failure: the instance is alive and answered.</li>
 * </ul>
 */
@Component
public class ResilientCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilientCaller.class);

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final double multiplier;

    public ResilientCaller(@Value("${sidis.resilience.retry.max-attempts:3}") int maxAttempts,
                           @Value("${sidis.resilience.retry.initial-backoff-ms:100}") long initialBackoffMs,
                           @Value("${sidis.resilience.retry.multiplier:2.0}") double multiplier) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = initialBackoffMs;
        this.multiplier = multiplier;
    }

    public <T> T call(EndpointHealth endpoint, boolean idempotent, Supplier<T> request) {
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
            } catch (HttpClientErrorException e) {
                endpoint.recordSuccess();   // it answered: alive
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
