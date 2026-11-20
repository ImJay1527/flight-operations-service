package pt.isep.sidis.flightops.readmodel;

/** The read database failed during a query; the caller falls back to the write side. */
public class ReadModelUnavailableException extends RuntimeException {

    public ReadModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
