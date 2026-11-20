package pt.isep.sidis.flightops.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.clients.AircraftDirectory;
import pt.isep.sidis.flightops.clients.RouteDirectory;
import pt.isep.sidis.flightops.common.metrics.FlightOpsMetrics;
import pt.isep.sidis.flightops.readmodel.ReadModel;
import pt.isep.sidis.flightops.readmodel.ReadModelState;
import pt.isep.sidis.flightops.readmodel.ReadModelUnavailableException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The query side: which side answers (CQRS read model, or the write side as fallback). */
@ExtendWith(MockitoExtension.class)
class FlightQueriesTest {

    @Mock ReadModel readModel;
    @Mock FlightQueryService writeSide;
    @Mock AircraftDirectory aircraftClient;
    @Mock RouteDirectory airportsRoutesClient;
    @Mock FlightOpsMetrics metrics;

    FlightQueries queries;

    private final LocalDateTime dep = LocalDateTime.now().plusDays(3).withNano(0);
    private final FlightView flight = new FlightView("F-1", "route-opo-lis", "CS-TPA", "A320neo", "OPO", "LIS",
            277, 3.8, dep, dep.plusMinutes(45), "SCHEDULED", 1);

    @BeforeEach
    void setUp() {
        queries = new FlightQueries(readModel, writeSide, aircraftClient, airportsRoutesClient, metrics);
    }

    private void readModelIs(boolean ready) {
        when(readModel.isReady()).thenReturn(ready);
        when(readModel.state()).thenReturn(new ReadModelState(ready ? ReadModelState.State.READY : ReadModelState.State.STALE));
    }

    @Test
    void readyReadModelAnswersAndTheWriteSideIsNotAsked() {
        readModelIs(true);
        when(readModel.flight("F-1")).thenReturn(Optional.of(flight));

        FlightLookup lookup = queries.flight("F-1");

        assertThat(lookup.flight()).isEqualTo(flight);
        assertThat(lookup.source()).isEqualTo("read-model");
        verifyNoInteractions(writeSide);
    }

    @Test
    void flightNotYetInTheReadModelIsLookedUpOnTheWriteSide() {
        // booked a moment ago on another instance: the change has not reached this read model yet
        readModelIs(true);
        when(readModel.flight("F-1")).thenReturn(Optional.empty());
        when(writeSide.findById("F-1")).thenReturn(FlightLookup.fromPeer(flight, "http://peer-2"));

        assertThat(queries.flight("F-1").source()).isEqualTo("peer:http://peer-2");
    }

    @Test
    void readModelNotReadyMeansTheWriteSideAnswers() {
        readModelIs(false);
        when(writeSide.findById("F-1")).thenReturn(FlightLookup.local(flight));

        assertThat(queries.flight("F-1").source()).isEqualTo("local");
    }

    @Test
    void readDatabaseFailingDuringAQueryFallsBackToTheWriteSide() {
        readModelIs(true);
        when(readModel.flightsOfAircraft("CS-TPA")).thenThrow(new ReadModelUnavailableException("down", null));
        when(writeSide.findByAircraft("CS-TPA")).thenReturn(List.of(flight));

        assertThat(queries.flightsOfAircraft("CS-TPA")).containsExactly(flight);
    }

    @Test
    void departuresComeFromTheReadModelForTheRequestedWindow() {
        readModelIs(true);
        when(readModel.departures(eq("OPO"), any(), any())).thenReturn(List.of(flight));

        assertThat(queries.departures("opo", 24)).containsExactly(flight);
        verify(airportsRoutesClient).getAirport("OPO");   // 404 for an airport Airports & Routes doesn't know
        verifyNoInteractions(writeSide);
    }

    @Test
    void departuresNeedAPositiveWindow() {
        assertThatThrownBy(() -> queries.departures("OPO", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
