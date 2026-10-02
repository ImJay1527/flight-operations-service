package pt.isep.sidis.flightops.resilience;

/** The instance was skipped without being called, because its circuit breaker is open. */
public class CircuitOpenException extends RuntimeException {
    public CircuitOpenException(String url) {
        super("circuit open for " + url + " (recently failing, skipped)");
    }
}
