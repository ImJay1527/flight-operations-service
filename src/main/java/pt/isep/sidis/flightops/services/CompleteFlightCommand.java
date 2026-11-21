package pt.isep.sidis.flightops.services;

/** Command (CQRS write side): "this flight has landed". */
public record CompleteFlightCommand(String flightNumber) {
}
