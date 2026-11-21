package pt.isep.sidis.flightops.readmodel;

import pt.isep.sidis.flightops.api.dto.AircraftFuelEfficiencyDTO;
import pt.isep.sidis.flightops.api.dto.AircraftUtilizationDTO;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.api.dto.RouteFuelEfficiencyDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The read side of CQRS: every flight of the whole cluster, and the reports already computed, in this instance's
 * own read database ({@link MongoReadModel}). Fed by the write side's {@code FlightChanged} events and repaired by
 * {@link ReadModelSync}. Queries use it only while {@link #isReady()}; otherwise they take the fallback path (the
 * write side, asking the peers as in Assignment 1).
 *
 * <p>The query methods throw {@link ReadModelUnavailableException} if the read database fails; the read model then
 * becomes STALE until the next successful sync.
 */
public interface ReadModel {

    ReadModelState state();

    default boolean isReady() {
        return state().current().state() == ReadModelState.State.READY;
    }

    /** Applies a flight's new state (newest revision wins). Never throws: a failure only makes the model STALE. */
    void project(FlightView flight);

    Optional<FlightView> flight(String flightNumber);

    List<FlightView> flightsOfAircraft(String registration);

    /** Every non-cancelled flight of the cluster, by departure time. */
    List<FlightView> activeFlights();

    /** Non-cancelled flights leaving {@code originIata} between the two times, by departure time. */
    List<FlightView> departures(String originIata, LocalDateTime from, LocalDateTime to);

    List<AircraftUtilizationDTO> utilization();

    Optional<AircraftUtilizationDTO> utilization(String registration);

    List<AircraftFuelEfficiencyDTO> aircraftFuel();

    Optional<AircraftFuelEfficiencyDTO> aircraftFuel(String registration);

    List<RouteFuelEfficiencyDTO> routeFuel();

    Optional<RouteFuelEfficiencyDTO> routeFuel(String routeId);
}
