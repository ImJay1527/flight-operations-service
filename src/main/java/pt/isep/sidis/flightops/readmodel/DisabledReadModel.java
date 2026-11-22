package pt.isep.sidis.flightops.readmodel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.api.dto.AircraftFuelEfficiencyDTO;
import pt.isep.sidis.flightops.api.dto.AircraftUtilizationDTO;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.api.dto.RouteFuelEfficiencyDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * No read database (run without the "cqrs" profile: unit tests, {@code run-local.sh --h2}): never ready, so every
 * query takes the fallback path. The query methods are never called.
 */
@Component
@ConditionalOnProperty(name = "flightops.read-model.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledReadModel implements ReadModel {

    private final ReadModelState state = new ReadModelState(ReadModelState.State.DISABLED);

    @Override
    public ReadModelState state() {
        return state;
    }

    @Override
    public void project(FlightView flight) {
        // nothing to update
    }

    @Override
    public Optional<FlightView> flight(String flightNumber) {
        throw unavailable();
    }

    @Override
    public List<FlightView> flightsOfAircraft(String registration) {
        throw unavailable();
    }

    @Override
    public List<FlightView> departures(String originIata, LocalDateTime from, LocalDateTime to) {
        throw unavailable();
    }

    @Override
    public List<AircraftUtilizationDTO> utilization() {
        throw unavailable();
    }

    @Override
    public Optional<AircraftUtilizationDTO> utilization(String registration) {
        throw unavailable();
    }

    @Override
    public List<AircraftFuelEfficiencyDTO> aircraftFuel() {
        throw unavailable();
    }

    @Override
    public Optional<AircraftFuelEfficiencyDTO> aircraftFuel(String registration) {
        throw unavailable();
    }

    @Override
    public List<RouteFuelEfficiencyDTO> routeFuel() {
        throw unavailable();
    }

    @Override
    public Optional<RouteFuelEfficiencyDTO> routeFuel(String routeId) {
        throw unavailable();
    }

    private static ReadModelUnavailableException unavailable() {
        return new ReadModelUnavailableException("No read database configured", null);
    }
}
