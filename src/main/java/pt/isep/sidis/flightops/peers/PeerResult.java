package pt.isep.sidis.flightops.peers;

import java.util.List;

/**
 * What came back from asking the peer replicas.
 *
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

    /** The 404 message, mentioning unreachable peers (the resource might exist there). */
    public String notFoundMessage(String base) {
        return isPartial()
                ? base + " (" + unreachable + " peer instance(s) could not be reached; it may exist there)"
                : base;
    }
}
