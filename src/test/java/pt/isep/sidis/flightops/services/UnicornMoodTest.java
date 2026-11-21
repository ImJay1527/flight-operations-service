package pt.isep.sidis.flightops.services;

import org.junit.jupiter.api.Test;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.api.dto.UnicornDTO;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnicornMoodTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 11, 21, 12, 0);

    private FlightView flight(LocalDateTime departure, int minutes, String status) {
        return new FlightView("F-" + departure + status, "route-opo-lis", "CS-TPA", "A320neo", "OPO", "LIS",
                277.0, 3.8, departure, departure.plusMinutes(minutes), status, 1);
    }

    private UnicornDTO mood(FlightView... flights) {
        return UnicornMood.of("CS-TPA", "A320neo", List.of(flights), now);
    }

    @Test
    void inTheAirNowIsFlying() {
        UnicornDTO m = mood(flight(now.minusMinutes(10), 60, "SCHEDULED"), flight(now.plusHours(3), 60, "SCHEDULED"));

        assertThat(m.mood()).isEqualTo("FLYING");
        assertThat(m.message()).contains("LIS");
    }

    @Test
    void leavingWithin24HoursIsExcited() {
        UnicornDTO m = mood(flight(now.plusHours(5), 45, "SCHEDULED"));

        assertThat(m.mood()).isEqualTo("EXCITED");
        assertThat(m.nextDeparture()).isEqualTo(now.plusHours(5));
        assertThat(m.upcomingFlights()).isEqualTo(1);
    }

    @Test
    void tenHoursInTheLastWeekIsTired() {
        UnicornDTO m = mood(flight(now.minusDays(2), 360, "COMPLETED"), flight(now.minusDays(1), 240, "COMPLETED"),
                flight(now.plusDays(3), 60, "SCHEDULED"));

        assertThat(m.mood()).isEqualTo("TIRED");
        assertThat(m.hoursLast7Days()).isEqualTo(10.0);
    }

    @Test
    void onlyThePartOfAFlightInsideTheWeekCounts() {
        // left 7 days and 2 hours ago, flew 3 hours: only its last hour is inside the week
        UnicornDTO m = mood(flight(now.minusDays(7).minusHours(2), 180, "COMPLETED"));

        assertThat(m.hoursLast7Days()).isEqualTo(1.0);
    }

    @Test
    void nothingScheduledIsBored() {
        assertThat(mood().mood()).isEqualTo("BORED");
        assertThat(mood(flight(now.minusDays(20), 60, "COMPLETED")).mood()).isEqualTo("BORED");
    }

    @Test
    void aTripPlannedLaterIsHappy() {
        UnicornDTO m = mood(flight(now.plusDays(5), 80, "SCHEDULED"));

        assertThat(m.mood()).isEqualTo("HAPPY");
        assertThat(m.message()).contains("2026-11-26");
    }

    @Test
    void cancelledFlightsDontCount() {
        UnicornDTO m = mood(flight(now.minusMinutes(10), 60, "CANCELED"), flight(now.plusHours(2), 60, "CANCELED"));

        assertThat(m.mood()).isEqualTo("BORED");
        assertThat(m.upcomingFlights()).isZero();
    }
}
