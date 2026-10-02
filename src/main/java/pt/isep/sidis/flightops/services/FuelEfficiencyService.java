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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * US227. Fuel burn rate = fuelCapacity / maxRange of the model, stored on each flight when it was scheduled.
 * Computed over the flights of all instances.
 */
@Service
@RequiredArgsConstructor
public class FuelEfficiencyService {

    private final FlightQueryService flightQueryService;
    private final AircraftDirectory aircraftClient;
    private final RouteDirectory airportsRoutesClient;

    public List<AircraftFuelEfficiencyDTO> getEfficiencyForAllAircraft() {
        return buildAircraftEfficiencyList(flightQueryService.findActive(null));
    }

    public AircraftFuelEfficiencyDTO getEfficiencyForAircraft(String registrationNumber) {
        String reg = registrationNumber.toUpperCase();
        AircraftInfo aircraft = aircraftClient.getAircraft(reg); // 404 if it does not exist

        List<FlightView> flights = flightQueryService.findActive(reg);
        if (flights.isEmpty()) {
            return zeroAircraftDTO(aircraft.registrationNumber(), aircraft.modelName(), aircraft.fuelBurnRate());
        }
        return buildAircraftEfficiencyList(flights).get(0);
    }

    public List<RouteFuelEfficiencyDTO> getEfficiencyForAllRoutes() {
        return buildRouteEfficiencyList(flightQueryService.findActive(null));
    }

    public RouteFuelEfficiencyDTO getEfficiencyForRoute(String routeId) {
        RouteInfo route = airportsRoutesClient.getRoute(routeId); // 404 if it does not exist

        List<FlightView> flights = flightQueryService.findActive(null).stream()
                .filter(sf -> sf.routeId().equals(routeId))
                .toList();

        if (flights.isEmpty()) {
            return new RouteFuelEfficiencyDTO(route.routeId(), route.origin().iataCode(),
                    route.destination().iataCode(), route.distanceKm(), 0, 0, 0);
        }
        return buildRouteEfficiencyList(flights).get(0);
    }

    private List<AircraftFuelEfficiencyDTO> buildAircraftEfficiencyList(List<FlightView> flights) {
        Map<String, AircraftAccumulator> map = new LinkedHashMap<>();
        for (FlightView sf : flights) {
            map.computeIfAbsent(sf.aircraftRegistration(),
                            k -> new AircraftAccumulator(sf.aircraftRegistration(), sf.aircraftModel(), sf.fuelBurnRate()))
               .add(sf.distanceKm());
        }
        List<AircraftFuelEfficiencyDTO> result = new ArrayList<>();
        for (AircraftAccumulator acc : map.values()) {
            result.add(acc.toDTO());
        }
        return result;
    }

    private List<RouteFuelEfficiencyDTO> buildRouteEfficiencyList(List<FlightView> flights) {
        Map<String, RouteAccumulator> map = new LinkedHashMap<>();
        for (FlightView sf : flights) {
            map.computeIfAbsent(sf.routeId(), k -> new RouteAccumulator(
                            sf.routeId(), sf.originIata(), sf.destinationIata(), sf.distanceKm()))
               .add(sf.fuelBurnRate());
        }
        List<RouteFuelEfficiencyDTO> result = new ArrayList<>();
        for (RouteAccumulator acc : map.values()) {
            result.add(acc.toDTO());
        }
        return result;
    }

    private AircraftFuelEfficiencyDTO zeroAircraftDTO(String reg, String modelName, double burnRate) {
        return new AircraftFuelEfficiencyDTO(reg, modelName, burnRate, 0, 0,
                burnRate > 0 ? 1.0 / burnRate : 0, 0);
    }

    private static class AircraftAccumulator {
        final String registrationNumber;
        final String modelName;
        final double burnRate;
        double totalDistance = 0;
        int flightCount = 0;

        AircraftAccumulator(String registrationNumber, String modelName, double burnRate) {
            this.registrationNumber = registrationNumber;
            this.modelName = modelName;
            this.burnRate = burnRate;
        }

        void add(double distanceKm) {
            totalDistance += distanceKm;
            flightCount++;
        }

        AircraftFuelEfficiencyDTO toDTO() {
            double totalFuel = burnRate * totalDistance;
            double efficiencyKmL = burnRate > 0 ? 1.0 / burnRate : 0;
            return new AircraftFuelEfficiencyDTO(registrationNumber, modelName,
                    burnRate, totalDistance, totalFuel, efficiencyKmL, flightCount);
        }
    }

    private static class RouteAccumulator {
        final String routeId;
        final String originIata;
        final String destinationIata;
        final double distanceKm;
        double totalBurnRate = 0;
        int flightCount = 0;

        RouteAccumulator(String routeId, String originIata, String destinationIata, double distanceKm) {
            this.routeId = routeId;
            this.originIata = originIata;
            this.destinationIata = destinationIata;
            this.distanceKm = distanceKm;
        }

        void add(double burnRate) {
            totalBurnRate += burnRate;
            flightCount++;
        }

        RouteFuelEfficiencyDTO toDTO() {
            double avgBurnRate = totalBurnRate / flightCount;
            double estimatedFuelPerFlight = avgBurnRate * distanceKm;
            double efficiencyKmL = avgBurnRate > 0 ? 1.0 / avgBurnRate : 0;
            return new RouteFuelEfficiencyDTO(routeId, originIata, destinationIata,
                    distanceKm, estimatedFuelPerFlight, efficiencyKmL, flightCount);
        }
    }
}
