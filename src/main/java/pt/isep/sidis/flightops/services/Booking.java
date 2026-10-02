package pt.isep.sidis.flightops.services;

import pt.isep.sidis.flightops.api.dto.FlightView;

/**
 * A booked flight and the instance that stores it (the aircraft's owner, or this instance as a fallback).
 * Sent to the client as the {@code X-Stored-On} header.
 */
public record Booking(FlightView flight, String storedOn) {
}
