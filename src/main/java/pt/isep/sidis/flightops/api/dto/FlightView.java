package pt.isep.sidis.flightops.api.dto;

import pt.isep.sidis.flightops.domain.FlightStatus;
import pt.isep.sidis.flightops.domain.ScheduledFlight;

import java.time.LocalDateTime;

/**
 * A flight as exchanged between instances on /internal/** and merged from local and remote results.
 * {@code revision} tells copies of the same flight apart: see {@link ScheduledFlight#updateFromCopy}.
 */
public record FlightView(
        String flightNumber,
        String routeId,
        String aircraftRegistration,
        String aircraftModel,
        String originIata,
        String destinationIata,
        double distanceKm,
        double fuelBurnRate,
        LocalDateTime scheduledDeparture,
        LocalDateTime scheduledArrival,
        String status,
        long revision) {

    public FlightView(String flightNumber, String routeId, String aircraftRegistration, String aircraftModel,
                      String originIata, String destinationIata, double distanceKm, double fuelBurnRate,
                      LocalDateTime scheduledDeparture, LocalDateTime scheduledArrival, String status) {
        this(flightNumber, routeId, aircraftRegistration, aircraftModel, originIata, destinationIata, distanceKm,
                fuelBurnRate, scheduledDeparture, scheduledArrival, status, 1);
    }

    public static FlightView of(ScheduledFlight f) {
        return new FlightView(f.getFlightNumber(), f.getRouteId(), f.getAircraftRegistration(), f.getAircraftModel(),
                f.getOriginIata(), f.getDestinationIata(), f.getDistanceKm(), f.getFuelBurnRate(),
                f.getScheduledDeparture(), f.getScheduledArrival(), f.getStatus().name(), f.getRevision());
    }

    /** Of two copies of the same flight, the newer one (same rule as {@link ScheduledFlight#updateFromCopy}). */
    public static FlightView newest(FlightView a, FlightView b) {
        if (a.revision != b.revision) {
            return a.revision > b.revision ? a : b;
        }
        return FlightStatus.valueOf(b.status).ordinal() > FlightStatus.valueOf(a.status).ordinal() ? b : a;
    }
}
