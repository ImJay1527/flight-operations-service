package pt.isep.sidis.flightops.services;

/** Command (CQRS write side): "cancel this flight". */
public record CancelFlightCommand(String flightNumber) {
}
