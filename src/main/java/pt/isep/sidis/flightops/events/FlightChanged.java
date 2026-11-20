package pt.isep.sidis.flightops.events;

import pt.isep.sidis.flightops.api.dto.FlightView;

/**
 * Domain event of the write side (CQRS): a flight was booked or changed on this instance, or this instance learnt a
 * newer copy of it. Published with Spring's ApplicationEventPublisher inside the write transaction; the read side
 * ({@code readmodel}) projects it once the transaction has committed. The write side doesn't know the read side.
 *
 * @param flight the flight's state after the change (with its revision)
 */
public record FlightChanged(FlightView flight) {
}
