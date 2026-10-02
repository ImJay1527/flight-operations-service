package pt.isep.sidis.flightops.services;

import java.time.LocalDateTime;

/** Body of POST /internal/flights: a booking forwarded to the instance that owns the aircraft. */
public record InternalBookingRequest(String routeId, String aircraftRegistration,
                                     LocalDateTime departureTime, LocalDateTime arrivalTime) {
}
