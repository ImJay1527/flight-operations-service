package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pt.isep.sidis.flightops.api.dto.AircraftFuelEfficiencyDTO;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.api.dto.RouteFuelEfficiencyDTO;
import pt.isep.sidis.flightops.clients.AircraftDirectory;
import pt.isep.sidis.flightops.clients.AircraftInfo;
import pt.isep.sidis.flightops.clients.RouteDirectory;
import pt.isep.sidis.flightops.clients.RouteInfo;
import pt.isep.sidis.flightops.readmodel.FlightStatistics;
import pt.isep.sidis.flightops.readmodel.ReadModel;

import java.util.List;

/**
 * US227 (query side). Fuel burn rate = fuelCapacity / maxRange of the model, stored on each flight when it was
 * scheduled. From the read model, where the reports are already computed; on the fallback path computed from the
 * flights of all instances.
 */
@Service
@RequiredArgsConstructor
public class FuelEfficiencyService {

    private final FlightQueryService writeSide;
    private final ReadModel readModel;
    private final FlightQueries queries;
    private final AircraftDirectory aircraftClient;
    private final RouteDirectory airportsRoutesClient;

    public List<AircraftFuelEfficiencyDTO> getEfficiencyForAllAircraft() {
        return queries.answer(readModel::aircraftFuel,
                () -> FlightStatistics.aircraftFuel(writeSide.findActive(null)));
    }

    /** An aircraft Aircraft & Maintenance knows but that has no flights yet: zero distance and fuel. */
    public AircraftFuelEfficiencyDTO getEfficiencyForAircraft(String registrationNumber) {
        String reg = registrationNumber.toUpperCase();
        AircraftInfo aircraft = aircraftClient.getAircraft(reg); // 404 if it does not exist
        return queries.answer(() -> readModel.aircraftFuel(reg),
                        () -> FlightStatistics.aircraftFuel(writeSide.findActive(reg)).stream().findFirst())
                .orElseGet(() -> FlightStatistics.zeroAircraftFuel(aircraft.registrationNumber(), aircraft.modelName(),
                        aircraft.fuelBurnRate()));
    }

    public List<RouteFuelEfficiencyDTO> getEfficiencyForAllRoutes() {
        return queries.answer(readModel::routeFuel,
                () -> FlightStatistics.routeFuel(writeSide.findActive(null)));
    }

    /** A route Airports & Routes knows but that has no flights yet: zero flights. */
    public RouteFuelEfficiencyDTO getEfficiencyForRoute(String routeId) {
        RouteInfo route = airportsRoutesClient.getRoute(routeId); // 404 if it does not exist
        return queries.answer(() -> readModel.routeFuel(routeId),
                        () -> FlightStatistics.routeFuel(onRoute(routeId)).stream().findFirst())
                .orElseGet(() -> new RouteFuelEfficiencyDTO(route.routeId(), route.origin().iataCode(),
                        route.destination().iataCode(), route.distanceKm(), 0, 0, 0));
    }

    private List<FlightView> onRoute(String routeId) {
        return writeSide.findActive(null).stream().filter(f -> f.routeId().equals(routeId)).toList();
    }
}
