package pt.isep.sidis.flightops.replication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.domain.FlightStatus;
import pt.isep.sidis.flightops.domain.ScheduledFlight;
import pt.isep.sidis.flightops.monitoring.FlightOpsMetrics;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Applying copies received from other instances: newest wins, order and repeats don't matter. */
@ExtendWith(MockitoExtension.class)
class ReplicaStoreTest {

    @Mock ScheduledFlightRepository flights;
    @Mock FlightOpsMetrics metrics;

    @InjectMocks ReplicaStore store;

    private final LocalDateTime dep = LocalDateTime.of(2030, 5, 1, 8, 0);

    private FlightView copy(String number, String status, long revision, LocalDateTime departure) {
        return new FlightView(number, "route-opo-lis", "CS-TPA", "A320neo", "OPO", "LIS", 277.0, 3.8,
                departure, departure.plusMinutes(45), status, revision);
    }

    private ScheduledFlight stored(FlightView v) {
        ScheduledFlight f = new ScheduledFlight(v.flightNumber(), v.routeId(), v.aircraftRegistration(), v.aircraftModel(),
                v.originIata(), v.destinationIata(), v.distanceKm(), v.fuelBurnRate(),
                v.scheduledDeparture(), v.scheduledArrival());
        f.restoreCopyState(FlightStatus.valueOf(v.status()), v.revision());
        return f;
    }

    @Test
    void unknownFlightIsStoredWithTheCopysState() {
        when(flights.findById("F-1")).thenReturn(Optional.empty());

        assertThat(store.apply(copy("F-1", "CANCELED", 2, dep))).isTrue();

        verify(flights).save(argThat(f -> f.getFlightNumber().equals("F-1")
                && f.getStatus() == FlightStatus.CANCELED && f.getRevision() == 2));
    }

    @Test
    void newerCopyReplacesTheStateHere() {
        ScheduledFlight here = stored(copy("F-1", "SCHEDULED", 1, dep));
        when(flights.findById("F-1")).thenReturn(Optional.of(here));

        assertThat(store.apply(copy("F-1", "CANCELED", 2, dep))).isTrue();

        assertThat(here.getStatus()).isEqualTo(FlightStatus.CANCELED);
        assertThat(here.getRevision()).isEqualTo(2);
    }

    @Test
    void olderCopyArrivingLateIsIgnored() {
        ScheduledFlight here = stored(copy("F-1", "CANCELED", 2, dep));
        when(flights.findById("F-1")).thenReturn(Optional.of(here));

        assertThat(store.apply(copy("F-1", "SCHEDULED", 1, dep))).isFalse();

        assertThat(here.getStatus()).isEqualTo(FlightStatus.CANCELED);
    }

    @Test
    void applyingTheSameCopyTwiceChangesNothing() {
        ScheduledFlight here = stored(copy("F-1", "CANCELED", 2, dep));
        when(flights.findById("F-1")).thenReturn(Optional.of(here));

        assertThat(store.apply(copy("F-1", "CANCELED", 2, dep))).isFalse();
        verify(flights, never()).save(any());
    }

    @Test
    void sameRevisionDifferentStatusResolvesTheSameWayInAnyOrder() {
        // e.g. data from before revisions existed: both copies say 1, one is cancelled
        ScheduledFlight a = stored(copy("F-1", "SCHEDULED", 1, dep));
        a.updateFromCopy(FlightStatus.CANCELED, 1);
        ScheduledFlight b = stored(copy("F-1", "CANCELED", 1, dep));
        b.updateFromCopy(FlightStatus.SCHEDULED, 1);

        assertThat(a.getStatus()).isEqualTo(FlightStatus.CANCELED);
        assertThat(b.getStatus()).isEqualTo(FlightStatus.CANCELED);
        assertThat(FlightView.newest(copy("F-1", "SCHEDULED", 1, dep), copy("F-1", "CANCELED", 1, dep)).status())
                .isEqualTo("CANCELED");
        assertThat(FlightView.newest(copy("F-1", "CANCELED", 1, dep), copy("F-1", "SCHEDULED", 1, dep)).status())
                .isEqualTo("CANCELED");
    }

    @Test
    void overlappingBookingMadeOnAnotherInstanceIsKeptAndReportedAsConflict() {
        // two instances took a booking for CS-TPA at the same time while they could not reach each other
        ScheduledFlight bookedHere = stored(copy("F-HERE", "SCHEDULED", 1, dep));
        when(flights.findById("F-THERE")).thenReturn(Optional.empty());
        when(flights.findByAircraftRegistration("CS-TPA")).thenReturn(List.of(bookedHere));

        assertThat(store.apply(copy("F-THERE", "SCHEDULED", 1, dep.plusMinutes(20)))).isTrue();

        verify(flights).save(any());
        verify(metrics).recordReplication("conflict");
    }

    @Test
    void nonOverlappingFlightOfTheSameAircraftIsNoConflict() {
        ScheduledFlight bookedHere = stored(copy("F-HERE", "SCHEDULED", 1, dep));
        when(flights.findById("F-LATER")).thenReturn(Optional.empty());
        when(flights.findByAircraftRegistration("CS-TPA")).thenReturn(List.of(bookedHere));

        store.apply(copy("F-LATER", "SCHEDULED", 1, dep.plusHours(5)));

        verify(metrics, never()).recordReplication("conflict");
    }
}
