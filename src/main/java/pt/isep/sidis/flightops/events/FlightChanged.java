package pt.isep.sidis.flightops.events;

import pt.isep.sidis.flightops.api.dto.FlightView;

/**
 * Domain event of the write side (CQRS): a flight was booked or changed on this instance, or this instance learnt a
 * newer copy of it. Published with Spring's ApplicationEventPublisher inside the write transaction; the read side
 * ({@code readmodel}) projects it once the transaction has committed, and {@code messaging} puts the changes made
 * here on the broker. The write side knows neither of them.
 *
 * @param flight   the flight's state after the change (with its revision)
 * @param madeHere true if this instance made the change (a command it handled); false if it learnt it from another
 *                 instance (a copy, or a peer's answer), which has already announced it
 */
public record FlightChanged(FlightView flight, boolean madeHere) {

    public static FlightChanged madeHere(FlightView flight) {
        return new FlightChanged(flight, true);
    }

    public static FlightChanged learnt(FlightView flight) {
        return new FlightChanged(flight, false);
    }
}
