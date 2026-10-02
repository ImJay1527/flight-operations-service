package pt.isep.sidis.flightops.common.exceptions;

/** A downstream service (or every replica of it) could not be reached. Mapped to HTTP 503. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
