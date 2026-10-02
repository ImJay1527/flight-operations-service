package pt.isep.sidis.flightops.api;

import org.junit.jupiter.api.Test;
import pt.isep.sidis.flightops.api.dto.AircraftFuelEfficiencyDTO;
import pt.isep.sidis.flightops.api.dto.RouteFuelEfficiencyDTO;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.common.exceptions.ServiceUnavailableException;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US227, ported from the PSOFT FuelEfficiencyControllerTest, plus the access rules and a service outage. */
class FuelEfficiencyControllerTest extends ReportControllerTestBase {

    //                                                     reg       model             L/km   km      fuel L  km/L  flights
    private final AircraftFuelEfficiencyDTO dtoA = new AircraftFuelEfficiencyDTO("CS-TUA", "Boeing 737-800", 4.0, 2000.0, 8000.0, 0.25, 2);
    private final AircraftFuelEfficiencyDTO dtoB = new AircraftFuelEfficiencyDTO("CS-TUB", "Airbus A320", 5.0, 500.0, 2500.0, 0.2, 1);
    private final AircraftFuelEfficiencyDTO dtoZeroFlights = new AircraftFuelEfficiencyDTO("CS-NEW", "Boeing 737-800", 4.0, 0.0, 0.0, 0.25, 0);

    //                                                route      from   to     km      fuel/flight  km/L  flights
    private final RouteFuelEfficiencyDTO routeDtoX = new RouteFuelEfficiencyDTO("ROUTE-X", "LIS", "OPO", 1000.0, 4000.0, 0.25, 1);
    private final RouteFuelEfficiencyDTO routeDtoY = new RouteFuelEfficiencyDTO("ROUTE-Y", "FAO", "MAD", 500.0, 2500.0, 0.2, 1);
    private final RouteFuelEfficiencyDTO routeDtoZero = new RouteFuelEfficiencyDTO("ROUTE-Z", "LIS", "MAD", 1500.0, 0.0, 0.0, 0);

    @Test
    void ensureGetAllAircraftEfficiencyReturns200() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllAircraft()).thenReturn(List.of(dtoA, dtoB));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft"))
                .andExpect(status().isOk());
    }

    @Test
    void ensureGetAllAircraftEfficiencyReturnsBothAircraft() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllAircraft()).thenReturn(List.of(dtoA, dtoB));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.aircraftFuelEfficiencyDTOList.length()").value(2));
    }

    @Test
    void ensureGetAllAircraftEfficiencyReturnsCorrectRegistrationNumbers() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllAircraft()).thenReturn(List.of(dtoA, dtoB));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.aircraftFuelEfficiencyDTOList[0].registrationNumber").value("CS-TUA"))
                .andExpect(jsonPath("$._embedded.aircraftFuelEfficiencyDTOList[1].registrationNumber").value("CS-TUB"));
    }

    @Test
    void ensureGetAllAircraftEfficiencyReturnsFuelMetrics() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllAircraft()).thenReturn(List.of(dtoA));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.aircraftFuelEfficiencyDTOList[0].fuelBurnRateLPerKm").value(4.0))
                .andExpect(jsonPath("$._embedded.aircraftFuelEfficiencyDTOList[0].totalDistanceFlownKm").value(2000.0))
                .andExpect(jsonPath("$._embedded.aircraftFuelEfficiencyDTOList[0].totalEstimatedFuelL").value(8000.0))
                .andExpect(jsonPath("$._embedded.aircraftFuelEfficiencyDTOList[0].efficiencyKmPerL").value(0.25))
                .andExpect(jsonPath("$._embedded.aircraftFuelEfficiencyDTOList[0].flightCount").value(2));
    }

    @Test
    void ensureGetAllAircraftEfficiencyContainsSelfLinks() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllAircraft()).thenReturn(List.of(dtoA));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.self").exists())
                .andExpect(jsonPath("$._links.route-efficiency").exists())
                .andExpect(jsonPath("$._embedded.aircraftFuelEfficiencyDTOList[0]._links.self").exists());
    }

    @Test
    void ensureGetAllAircraftEfficiencyReturnsEmptyCollectionWhenNoFlights() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllAircraft()).thenReturn(List.of());

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist())
                .andExpect(jsonPath("$._links.self").exists());
    }

    @Test
    void ensureGetAircraftEfficiencyReturns200() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAircraft("CS-TUA")).thenReturn(dtoA);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft/CS-TUA"))
                .andExpect(status().isOk());
    }

    @Test
    void ensureGetAircraftEfficiencyReturnsCorrectRegistration() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAircraft("CS-TUA")).thenReturn(dtoA);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft/CS-TUA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationNumber").value("CS-TUA"));
    }

    @Test
    void ensureGetAircraftEfficiencyReturnsModelName() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAircraft("CS-TUA")).thenReturn(dtoA);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft/CS-TUA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value("Boeing 737-800"));
    }

    @Test
    void ensureGetAircraftEfficiencyReturnsFuelMetrics() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAircraft("CS-TUA")).thenReturn(dtoA);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft/CS-TUA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fuelBurnRateLPerKm").value(4.0))
                .andExpect(jsonPath("$.totalDistanceFlownKm").value(2000.0))
                .andExpect(jsonPath("$.totalEstimatedFuelL").value(8000.0))
                .andExpect(jsonPath("$.efficiencyKmPerL").value(0.25))
                .andExpect(jsonPath("$.flightCount").value(2));
    }

    @Test
    void ensureGetAircraftEfficiencyContainsSelfAndCollectionLinks() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAircraft("CS-TUA")).thenReturn(dtoA);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft/CS-TUA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.self").exists())
                .andExpect(jsonPath("$._links.all-aircraft-efficiency").exists());
    }

    @Test
    void ensureGetAircraftEfficiencyReturnsZeroMetricsWhenNoFlights() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAircraft("CS-NEW")).thenReturn(dtoZeroFlights);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft/CS-NEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationNumber").value("CS-NEW"))
                .andExpect(jsonPath("$.flightCount").value(0))
                .andExpect(jsonPath("$.totalDistanceFlownKm").value(0.0))
                .andExpect(jsonPath("$.totalEstimatedFuelL").value(0.0));
    }

    @Test
    void ensureGetAircraftEfficiencyReturns404WhenNotFound() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAircraft("UNKNOWN"))
                .thenThrow(new ResourceNotFoundException("Aircraft not found with registration: UNKNOWN"));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft/UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ensureGetAircraftEfficiencyReturns503WhenAircraftServiceIsDown() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAircraft("CS-TUA"))
                .thenThrow(new ServiceUnavailableException("aircraft-maintenance-service: no instance reachable"));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/aircraft/CS-TUA"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void ensureGetAllRouteEfficiencyReturns200() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllRoutes()).thenReturn(List.of(routeDtoX, routeDtoY));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes"))
                .andExpect(status().isOk());
    }

    @Test
    void ensureGetAllRouteEfficiencyReturnsBothRoutes() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllRoutes()).thenReturn(List.of(routeDtoX, routeDtoY));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.routeFuelEfficiencyDTOList.length()").value(2));
    }

    @Test
    void ensureGetAllRouteEfficiencyReturnsCorrectRouteIds() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllRoutes()).thenReturn(List.of(routeDtoX, routeDtoY));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.routeFuelEfficiencyDTOList[0].routeId").value("ROUTE-X"))
                .andExpect(jsonPath("$._embedded.routeFuelEfficiencyDTOList[1].routeId").value("ROUTE-Y"));
    }

    @Test
    void ensureGetAllRouteEfficiencyReturnsFuelMetrics() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllRoutes()).thenReturn(List.of(routeDtoX));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.routeFuelEfficiencyDTOList[0].originIata").value("LIS"))
                .andExpect(jsonPath("$._embedded.routeFuelEfficiencyDTOList[0].destinationIata").value("OPO"))
                .andExpect(jsonPath("$._embedded.routeFuelEfficiencyDTOList[0].distanceKm").value(1000.0))
                .andExpect(jsonPath("$._embedded.routeFuelEfficiencyDTOList[0].estimatedFuelPerFlightL").value(4000.0))
                .andExpect(jsonPath("$._embedded.routeFuelEfficiencyDTOList[0].efficiencyKmPerL").value(0.25))
                .andExpect(jsonPath("$._embedded.routeFuelEfficiencyDTOList[0].flightCount").value(1));
    }

    @Test
    void ensureGetAllRouteEfficiencyContainsSelfLinks() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllRoutes()).thenReturn(List.of(routeDtoX));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.self").exists())
                .andExpect(jsonPath("$._links.aircraft-efficiency").exists())
                .andExpect(jsonPath("$._embedded.routeFuelEfficiencyDTOList[0]._links.self").exists());
    }

    @Test
    void ensureGetAllRouteEfficiencyReturnsEmptyCollectionWhenNoRoutes() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForAllRoutes()).thenReturn(List.of());

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist())
                .andExpect(jsonPath("$._links.self").exists());
    }

    @Test
    void ensureGetRouteEfficiencyReturns200() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForRoute("ROUTE-X")).thenReturn(routeDtoX);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes/ROUTE-X"))
                .andExpect(status().isOk());
    }

    @Test
    void ensureGetRouteEfficiencyReturnsCorrectRouteId() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForRoute("ROUTE-X")).thenReturn(routeDtoX);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes/ROUTE-X"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value("ROUTE-X"));
    }

    @Test
    void ensureGetRouteEfficiencyReturnsAirportCodes() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForRoute("ROUTE-X")).thenReturn(routeDtoX);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes/ROUTE-X"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originIata").value("LIS"))
                .andExpect(jsonPath("$.destinationIata").value("OPO"));
    }

    @Test
    void ensureGetRouteEfficiencyReturnsFuelMetrics() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForRoute("ROUTE-X")).thenReturn(routeDtoX);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes/ROUTE-X"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceKm").value(1000.0))
                .andExpect(jsonPath("$.estimatedFuelPerFlightL").value(4000.0))
                .andExpect(jsonPath("$.efficiencyKmPerL").value(0.25))
                .andExpect(jsonPath("$.flightCount").value(1));
    }

    @Test
    void ensureGetRouteEfficiencyContainsSelfAndCollectionLinks() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForRoute("ROUTE-X")).thenReturn(routeDtoX);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes/ROUTE-X"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.self").exists())
                .andExpect(jsonPath("$._links.all-route-efficiency").exists());
    }

    @Test
    void ensureGetRouteEfficiencyReturnsZeroMetricsWhenNoFlights() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForRoute("ROUTE-Z")).thenReturn(routeDtoZero);

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes/ROUTE-Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value("ROUTE-Z"))
                .andExpect(jsonPath("$.flightCount").value(0))
                .andExpect(jsonPath("$.estimatedFuelPerFlightL").value(0.0))
                .andExpect(jsonPath("$.efficiencyKmPerL").value(0.0));
    }

    @Test
    void ensureGetRouteEfficiencyReturns404WhenNotFound() throws Exception {
        when(fuelEfficiencyService.getEfficiencyForRoute("NONEXISTENT"))
                .thenThrow(new ResourceNotFoundException("Flight route not found with ID: NONEXISTENT"));

        mockMvc.perform(getAsAtcc("/api/fuel-efficiency/routes/NONEXISTENT"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ensureOtherRolesGet403() throws Exception {
        mockMvc.perform(getAs("BACKOFFICE_OPERATOR", "/api/fuel-efficiency/aircraft"))
                .andExpect(status().isForbidden());
        mockMvc.perform(getAs("MAINTENANCE_SUPERVISOR", "/api/fuel-efficiency/routes/ROUTE-X"))
                .andExpect(status().isForbidden());
    }
}
