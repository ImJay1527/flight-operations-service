package pt.isep.sidis.flightops.services;

import pt.isep.sidis.flightops.api.dto.FlightView;

import java.util.List;

/**
 * A booked flight, the instance that made the booking ({@code X-Stored-On} header) and all instances that keep a
 * copy of it ({@code X-Replicas} header).
 */
public record Booking(FlightView flight, String storedOn, List<String> replicas) {
}
