package pt.isep.sidis.flightops.api.dto;

import java.time.LocalDateTime;

/**
 * The mood of one airplane (GET /api/v2/unicorns).
 *
 * @param mood             FLYING, EXCITED, TIRED, BORED or HAPPY
 * @param message          the mood in a few words
 * @param nextDeparture    its next scheduled departure (null if none)
 * @param hoursLast7Days   hours flown in the last 7 days
 * @param upcomingFlights  scheduled flights that have not left yet
 */
public record UnicornDTO(
        String registration,
        String model,
        String mood,
        String message,
        LocalDateTime nextDeparture,
        double hoursLast7Days,
        int upcomingFlights) {
}
