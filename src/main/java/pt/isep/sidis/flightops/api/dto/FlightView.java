package pt.isep.sidis.flightops.api.dto;

import pt.isep.sidis.flightops.domain.ScheduledFlight;

import java.time.LocalDateTime;

/** A flight as exchanged between instances on /internal/flights/** and merged from local and remote results. */
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
        String status) {

    public static FlightView of(ScheduledFlight f) {
        return new FlightView(f.getFlightNumber(), f.getRouteId(), f.getAircraftRegistration(), f.getAircraftModel(),
                f.getOriginIata(), f.getDestinationIata(), f.getDistanceKm(), f.getFuelBurnRate(),
                f.getScheduledDeparture(), f.getScheduledArrival(), f.getStatus().name());
    }
}
