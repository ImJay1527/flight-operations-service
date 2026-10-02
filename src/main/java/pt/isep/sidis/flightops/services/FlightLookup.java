package pt.isep.sidis.flightops.services;

import pt.isep.sidis.flightops.api.dto.FlightView;

/** A flight and where it was found ({@code local} or {@code peer:<url>}), sent as the {@code X-Data-Source} header. */
public record FlightLookup(FlightView flight, String source) {

    public static final String LOCAL = "local";

    public static FlightLookup local(FlightView flight) {
        return new FlightLookup(flight, LOCAL);
    }

    public static FlightLookup fromPeer(FlightView flight, String peerUrl) {
        return new FlightLookup(flight, "peer:" + peerUrl);
    }
}
