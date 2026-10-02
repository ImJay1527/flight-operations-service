package pt.isep.sidis.flightops.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pt.isep.sidis.flightops.api.dto.AircraftFuelEfficiencyDTO;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.api.dto.RouteFuelEfficiencyDTO;
import pt.isep.sidis.flightops.clients.AircraftDirectory;
import pt.isep.sidis.flightops.clients.AircraftInfo;
import pt.isep.sidis.flightops.clients.AirportInfo;
import pt.isep.sidis.flightops.clients.RouteDirectory;
import pt.isep.sidis.flightops.clients.RouteInfo;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.common.exceptions.ServiceUnavailableException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US227, ported from the PSOFT FuelEfficiencyServiceTest. The flights now come from FlightQueryService (all
 * instances), aircraft and routes from the other two services.
 */
@ExtendWith(MockitoExtension.class)
class FuelEfficiencyServiceTest {

    @Mock FlightQueryService flightQueryService;
    @Mock AircraftDirectory aircraftClient;
    @Mock RouteDirectory airportsRoutesClient;

    @InjectMocks FuelEfficiencyService service;

    // burn rate = fuel capacity / max range: 20000 / 5000 = 4.0 L/km (A), 10000 / 2000 = 5.0 L/km (B)
    private final AircraftInfo aircraftA = new AircraftInfo("CS-TUA", "AVAILABLE", "737-800", 5000.0, 20000.0, 189);
    private final RouteInfo routeX = new RouteInfo("ROUTE-X", "ACTIVE", 1000.0, 5500.0, 100,
            airport("LIS"), airport("OPO"));

    private final FlightView flightA1 = flight("A1", "ROUTE-X", "CS-TUA", "737-800", "LIS", "OPO", 1000.0, 4.0,
            LocalDateTime.of(2025, 3, 10, 8, 0), LocalDateTime.of(2025, 3, 10, 9, 30));
    private final FlightView flightA2 = flight("A2", "ROUTE-X", "CS-TUA", "737-800", "LIS", "OPO", 1000.0, 4.0,
            LocalDateTime.of(2025, 4, 1, 10, 0), LocalDateTime.of(2025, 4, 1, 11, 30));
    private final FlightView flightB1 = flight("B1", "ROUTE-Y", "CS-TUB", "A320", "FAO", "MAD", 500.0, 5.0,
            LocalDateTime.of(2025, 5, 5, 7, 0), LocalDateTime.of(2025, 5, 5, 7, 45));

    @Test
    void getEfficiencyForAllAircraft_withFlights_returnsCorrectMetrics() {
        when(flightQueryService.findActive(null)).thenReturn(List.of(flightA1, flightA2, flightB1));

        List<AircraftFuelEfficiencyDTO> result = service.getEfficiencyForAllAircraft();

        assertThat(result).hasSize(2);

        AircraftFuelEfficiencyDTO dtoA = byRegistration(result, "CS-TUA");
        assertThat(dtoA.getFuelBurnRateLPerKm()).isEqualTo(4.0);
        assertThat(dtoA.getTotalDistanceFlownKm()).isEqualTo(2000.0);
        assertThat(dtoA.getTotalEstimatedFuelL()).isEqualTo(8000.0);
        assertThat(dtoA.getEfficiencyKmPerL()).isEqualTo(0.25);
        assertThat(dtoA.getFlightCount()).isEqualTo(2);

        AircraftFuelEfficiencyDTO dtoB = byRegistration(result, "CS-TUB");
        assertThat(dtoB.getFuelBurnRateLPerKm()).isEqualTo(5.0);
        assertThat(dtoB.getTotalDistanceFlownKm()).isEqualTo(500.0);
        assertThat(dtoB.getTotalEstimatedFuelL()).isEqualTo(2500.0);
        assertThat(dtoB.getEfficiencyKmPerL()).isEqualTo(0.2);
        assertThat(dtoB.getFlightCount()).isEqualTo(1);
    }

    @Test
    void getEfficiencyForAllAircraft_noFlights_returnsEmptyList() {
        when(flightQueryService.findActive(null)).thenReturn(List.of());

        assertThat(service.getEfficiencyForAllAircraft()).isEmpty();
    }

    @Test
    void getEfficiencyForAircraft_withFlights_returnsCorrectMetrics() {
        when(aircraftClient.getAircraft("CS-TUA")).thenReturn(aircraftA);
        when(flightQueryService.findActive("CS-TUA")).thenReturn(List.of(flightA1, flightA2));

        AircraftFuelEfficiencyDTO dto = service.getEfficiencyForAircraft("CS-TUA");

        assertThat(dto.getRegistrationNumber()).isEqualTo("CS-TUA");
        assertThat(dto.getFuelBurnRateLPerKm()).isEqualTo(4.0);
        assertThat(dto.getTotalDistanceFlownKm()).isEqualTo(2000.0);
        assertThat(dto.getFlightCount()).isEqualTo(2);
    }

    @Test
    void getEfficiencyForAircraft_registrationIsUppercased() {
        when(aircraftClient.getAircraft("CS-TUA")).thenReturn(aircraftA);
        when(flightQueryService.findActive("CS-TUA")).thenReturn(List.of(flightA1));

        AircraftFuelEfficiencyDTO dto = service.getEfficiencyForAircraft("cs-tua");

        assertThat(dto.getRegistrationNumber()).isEqualTo("CS-TUA");
        verify(aircraftClient).getAircraft("CS-TUA");
        verify(flightQueryService).findActive("CS-TUA");
    }

    @Test
    void getEfficiencyForAircraft_aircraftNotFound_throwsResourceNotFoundException() {
        when(aircraftClient.getAircraft("UNKNOWN"))
                .thenThrow(new ResourceNotFoundException("Aircraft not found with registration: UNKNOWN"));

        assertThatThrownBy(() -> service.getEfficiencyForAircraft("UNKNOWN"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("UNKNOWN");
        verify(flightQueryService, never()).findActive("UNKNOWN");
    }

    @Test
    void getEfficiencyForAircraft_aircraftServiceUnavailable_throwsInsteadOfReportingZero() {
        when(aircraftClient.getAircraft("CS-TUA"))
                .thenThrow(new ServiceUnavailableException("aircraft-maintenance-service unavailable"));

        assertThatThrownBy(() -> service.getEfficiencyForAircraft("CS-TUA"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void getEfficiencyForAircraft_noFlights_returnsZeroMetrics() {
        when(aircraftClient.getAircraft("CS-TUA")).thenReturn(aircraftA);
        when(flightQueryService.findActive("CS-TUA")).thenReturn(List.of());

        AircraftFuelEfficiencyDTO dto = service.getEfficiencyForAircraft("CS-TUA");

        assertThat(dto.getRegistrationNumber()).isEqualTo("CS-TUA");
        assertThat(dto.getModelName()).isEqualTo("737-800");
        assertThat(dto.getTotalDistanceFlownKm()).isEqualTo(0.0);
        assertThat(dto.getTotalEstimatedFuelL()).isEqualTo(0.0);
        assertThat(dto.getFlightCount()).isEqualTo(0);
        // the model's efficiency is still known without flights
        assertThat(dto.getEfficiencyKmPerL()).isEqualTo(0.25);
    }

    @Test
    void getEfficiencyForAllRoutes_withFlights_returnsCorrectMetrics() {
        when(flightQueryService.findActive(null)).thenReturn(List.of(flightA1, flightB1));

        List<RouteFuelEfficiencyDTO> result = service.getEfficiencyForAllRoutes();

        assertThat(result).hasSize(2);

        RouteFuelEfficiencyDTO dtoX = result.stream()
                .filter(d -> d.getRouteId().equals("ROUTE-X"))
                .findFirst().orElseThrow();
        assertThat(dtoX.getOriginIata()).isEqualTo("LIS");
        assertThat(dtoX.getDestinationIata()).isEqualTo("OPO");
        assertThat(dtoX.getDistanceKm()).isEqualTo(1000.0);
        assertThat(dtoX.getEstimatedFuelPerFlightL()).isEqualTo(4000.0);
        assertThat(dtoX.getEfficiencyKmPerL()).isEqualTo(0.25);
        assertThat(dtoX.getFlightCount()).isEqualTo(1);
    }

    @Test
    void getEfficiencyForAllRoutes_multipleModelsOnSameRoute_averagesBurnRate() {
        FlightView flightBonX = flight("B2", "ROUTE-X", "CS-TUB", "A320", "LIS", "OPO", 1000.0, 5.0,
                LocalDateTime.of(2025, 6, 1, 8, 0), LocalDateTime.of(2025, 6, 1, 9, 30));
        when(flightQueryService.findActive(null)).thenReturn(List.of(flightA1, flightBonX));

        List<RouteFuelEfficiencyDTO> result = service.getEfficiencyForAllRoutes();

        assertThat(result).hasSize(1);
        RouteFuelEfficiencyDTO dto = result.get(0);
        // average burn rate (4.0 + 5.0) / 2 = 4.5 L/km over 1000 km
        assertThat(dto.getEstimatedFuelPerFlightL()).isEqualTo(4500.0);
        assertThat(dto.getEfficiencyKmPerL()).isCloseTo(1.0 / 4.5, within(0.0001));
        assertThat(dto.getFlightCount()).isEqualTo(2);
    }

    @Test
    void getEfficiencyForAllRoutes_noFlights_returnsEmptyList() {
        when(flightQueryService.findActive(null)).thenReturn(List.of());

        assertThat(service.getEfficiencyForAllRoutes()).isEmpty();
    }

    @Test
    void getEfficiencyForRoute_withFlights_countsOnlyThatRoute() {
        when(airportsRoutesClient.getRoute("ROUTE-X")).thenReturn(routeX);
        when(flightQueryService.findActive(null)).thenReturn(List.of(flightA1, flightA2, flightB1));

        RouteFuelEfficiencyDTO dto = service.getEfficiencyForRoute("ROUTE-X");

        assertThat(dto.getRouteId()).isEqualTo("ROUTE-X");
        assertThat(dto.getFlightCount()).isEqualTo(2);
        assertThat(dto.getDistanceKm()).isEqualTo(1000.0);
    }

    @Test
    void getEfficiencyForRoute_routeNotFound_throwsResourceNotFoundException() {
        when(airportsRoutesClient.getRoute("NONEXISTENT"))
                .thenThrow(new ResourceNotFoundException("Flight Route not found with ID: NONEXISTENT"));

        assertThatThrownBy(() -> service.getEfficiencyForRoute("NONEXISTENT"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("NONEXISTENT");
    }

    @Test
    void getEfficiencyForRoute_noFlights_returnsZeroMetrics() {
        when(airportsRoutesClient.getRoute("ROUTE-X")).thenReturn(routeX);
        when(flightQueryService.findActive(null)).thenReturn(List.of());

        RouteFuelEfficiencyDTO dto = service.getEfficiencyForRoute("ROUTE-X");

        assertThat(dto.getRouteId()).isEqualTo("ROUTE-X");
        assertThat(dto.getOriginIata()).isEqualTo("LIS");
        assertThat(dto.getDestinationIata()).isEqualTo("OPO");
        assertThat(dto.getDistanceKm()).isEqualTo(1000.0);
        assertThat(dto.getFlightCount()).isEqualTo(0);
        assertThat(dto.getEstimatedFuelPerFlightL()).isEqualTo(0.0);
        assertThat(dto.getEfficiencyKmPerL()).isEqualTo(0.0);
    }

    @Test
    void getEfficiencyForAircraft_modelWithZeroBurnRate_doesNotReturnInfinity() {
        AircraftInfo zeroBurn = new AircraftInfo("CS-ZERO", "AVAILABLE", "ZeroModel", 1000.0, 0.0, 100);
        when(aircraftClient.getAircraft("CS-ZERO")).thenReturn(zeroBurn);
        when(flightQueryService.findActive("CS-ZERO")).thenReturn(List.of());

        AircraftFuelEfficiencyDTO dto = service.getEfficiencyForAircraft("CS-ZERO");

        assertThat(dto.getEfficiencyKmPerL()).isEqualTo(0.0);
        assertThat(Double.isInfinite(dto.getEfficiencyKmPerL())).isFalse();
        assertThat(Double.isNaN(dto.getEfficiencyKmPerL())).isFalse();
    }

    @Test
    void accumulators_withZeroBurnRateFlights_handleDivisionByZeroGracefully() {
        FlightView zeroFlight = flight("Z1", "ROUTE-X", "CS-ZERO", "Zero-E", "LIS", "OPO", 1000.0, 0.0,
                LocalDateTime.of(2025, 7, 1, 8, 0), LocalDateTime.of(2025, 7, 1, 9, 0));
        when(flightQueryService.findActive(null)).thenReturn(List.of(zeroFlight));

        List<AircraftFuelEfficiencyDTO> aircraftResult = service.getEfficiencyForAllAircraft();
        assertThat(aircraftResult).hasSize(1);
        assertThat(aircraftResult.get(0).getEfficiencyKmPerL()).isEqualTo(0.0);

        List<RouteFuelEfficiencyDTO> routeResult = service.getEfficiencyForAllRoutes();
        assertThat(routeResult).hasSize(1);
        assertThat(routeResult.get(0).getEfficiencyKmPerL()).isEqualTo(0.0);
    }

    private static AircraftFuelEfficiencyDTO byRegistration(List<AircraftFuelEfficiencyDTO> list, String reg) {
        return list.stream().filter(d -> d.getRegistrationNumber().equals(reg)).findFirst().orElseThrow();
    }

    private static AirportInfo airport(String iata) {
        return new AirportInfo(iata, "OPERATIONAL", List.of("737-800", "A320"));
    }

    private static FlightView flight(String number, String routeId, String reg, String model, String origin,
                                     String destination, double distanceKm, double burnRate,
                                     LocalDateTime departure, LocalDateTime arrival) {
        return new FlightView(number, routeId, reg, model, origin, destination, distanceKm, burnRate,
                departure, arrival, "SCHEDULED");
    }
}
