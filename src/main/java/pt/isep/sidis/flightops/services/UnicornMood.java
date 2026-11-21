package pt.isep.sidis.flightops.services;

import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.api.dto.UnicornDTO;
import pt.isep.sidis.flightops.domain.FlightStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * An airplane's mood, from its flights (the first rule that matches):
 * <ol>
 *   <li>FLYING - one of its flights is in the air now;</li>
 *   <li>EXCITED - it leaves within 24 hours;</li>
 *   <li>TIRED - it flew {@value #TIRED_HOURS} hours or more in the last 7 days;</li>
 *   <li>BORED - nothing is scheduled;</li>
 *   <li>HAPPY - otherwise: a trip is planned.</li>
 * </ol>
 * Cancelled flights don't count. Pure: the same flights at the same time always give the same mood.
 */
public final class UnicornMood {

    public static final double TIRED_HOURS = 10.0;

    private UnicornMood() {
    }

    public static UnicornDTO of(String registration, String model, List<FlightView> flights, LocalDateTime now) {
        List<FlightView> active = flights.stream()
                .filter(f -> !FlightStatus.CANCELED.name().equals(f.status()))
                .toList();
        List<FlightView> upcoming = active.stream()
                .filter(f -> isScheduled(f) && f.scheduledDeparture().isAfter(now))
                .sorted(Comparator.comparing(FlightView::scheduledDeparture))
                .toList();
        Optional<FlightView> inTheAir = active.stream()
                .filter(f -> isScheduled(f) && !f.scheduledDeparture().isAfter(now) && f.scheduledArrival().isAfter(now))
                .findFirst();
        double hours = hoursFlown(active, now.minusDays(7), now);
        FlightView next = upcoming.isEmpty() ? null : upcoming.get(0);

        String mood;
        String message;
        if (inTheAir.isPresent()) {
            mood = "FLYING";
            message = "Up in the air, on the way to " + inTheAir.get().destinationIata() + ".";
        } else if (next != null && next.scheduledDeparture().isBefore(now.plusHours(24))) {
            mood = "EXCITED";
            message = "Leaving for " + next.destinationIata() + " soon!";
        } else if (hours >= TIRED_HOURS) {
            mood = "TIRED";
            message = "Flew " + Math.round(hours) + " hours this week. Needs a rest.";
        } else if (next == null) {
            mood = "BORED";
            message = "Nothing planned. Book me a flight!";
        } else {
            mood = "HAPPY";
            message = "Next trip to " + next.destinationIata() + " on " + next.scheduledDeparture().toLocalDate() + ".";
        }
        return new UnicornDTO(registration, model, mood, message, next == null ? null : next.scheduledDeparture(),
                Math.round(hours * 100) / 100.0, upcoming.size());
    }

    private static boolean isScheduled(FlightView f) {
        return FlightStatus.SCHEDULED.name().equals(f.status());
    }

    /** Hours of the flights (or parts of them) between {@code from} and {@code to}. */
    private static double hoursFlown(List<FlightView> flights, LocalDateTime from, LocalDateTime to) {
        long minutes = 0;
        for (FlightView f : flights) {
            LocalDateTime start = f.scheduledDeparture().isBefore(from) ? from : f.scheduledDeparture();
            LocalDateTime end = f.scheduledArrival().isAfter(to) ? to : f.scheduledArrival();
            if (end.isAfter(start)) {
                minutes += Duration.between(start, end).toMinutes();
            }
        }
        return minutes / 60.0;
    }
}
