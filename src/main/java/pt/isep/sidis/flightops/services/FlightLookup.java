package pt.isep.sidis.flightops.services;

import pt.isep.sidis.flightops.api.dto.FlightView;

/**
 * A flight plus where it was found: {@code "local"} (this instance's database) or {@code "peer:<url>"}.
 * Sent to the client as the {@code X-Data-Source} header, so tests can see whether a request was forwarded.
 */
public record FlightLookup(FlightView flight, String source) {

    public static final String LOCAL = "local";

    public static FlightLookup local(FlightView flight) {
        return new FlightLookup(flight, LOCAL);
    }

    public static FlightLookup fromPeer(FlightView flight, String peerUrl) {
        return new FlightLookup(flight, "peer:" + peerUrl);
    }
}
