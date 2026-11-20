package pt.isep.sidis.flightops.services;

import java.time.LocalDateTime;

/** Command (CQRS write side): "assign this aircraft to this route at these times" (US212). */
public record ScheduleFlightCommand(String routeId, String aircraftRegistration,
                                    LocalDateTime departureTime, LocalDateTime arrivalTime) {
}
