package pt.isep.sidis.flightops.peers;

import java.util.List;

/**
 * @param items       everything the reachable peers returned
 * @param unreachable how many peers could not be asked (down, timeout, 5xx, circuit open)
 * @param answeredBy  for single-item lookups: base URL of the peer that had it (null otherwise)
 */
public record PeerResult<T>(List<T> items, int unreachable, String answeredBy) {

    public PeerResult(List<T> items, int unreachable) {
        this(items, unreachable, null);
    }

    public boolean isPartial() {
        return unreachable > 0;
    }

    /**
     * True if something not found may still exist on the peers that could not be asked. Every flight is kept on
     * {@code replicationFactor} instances, this one included: unless at least that many peers did not answer, one of
     * its copies was asked, so "not found" really means it does not exist (404). Otherwise we can't tell (503).
     */
    public boolean mayHideIt(int replicationFactor) {
        return unreachable > 0 && unreachable >= replicationFactor;
    }

    /** The 503 message when {@link #mayHideIt} is true; {@code what} e.g. "scheduled flight F-1". */
    public String unavailableMessage(String what) {
        return "Nothing found for " + what + " on the instances that answered, but " + unreachable
                + " instance(s) could not be reached and the data may be stored there. Try again later.";
    }
}
