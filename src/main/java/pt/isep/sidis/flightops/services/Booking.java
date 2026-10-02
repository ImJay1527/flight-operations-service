package pt.isep.sidis.flightops.services;

import pt.isep.sidis.flightops.api.dto.FlightView;

/** A booked flight and the instance that stores it, sent as the {@code X-Stored-On} header. */
public record Booking(FlightView flight, String storedOn) {
}
