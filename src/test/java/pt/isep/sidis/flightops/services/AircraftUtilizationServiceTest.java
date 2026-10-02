package pt.isep.sidis.flightops.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pt.isep.sidis.flightops.api.dto.AircraftUtilizationDTO;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US223, ported from the PSOFT AircraftUtilizationServiceTest. The flights now come from all instances. */
@ExtendWith(MockitoExtension.class)
class AircraftUtilizationServiceTest {

    @Mock FlightQueryService flightQueryService;

    @InjectMocks AircraftUtilizationService service;

    @Test
    void ensureGetUtilizationForAllAircraftReturnsEmptyListWhenNoFlights() {
        when(flightQueryService.findActive(null)).thenReturn(List.of());

        List<AircraftUtilizationDTO> result = service.getUtilizationForAllAircraft();

        assertTrue(result.isEmpty());
        verify(flightQueryService).findActive(null);
    }

    @Test
    void ensureGetUtilizationForAllAircraftGroupsFlightsByAircraft() {
        LocalDateTime dep1 = LocalDateTime.of(2025, 1, 10, 8, 0);
        LocalDateTime dep2 = LocalDateTime.of(2025, 1, 15, 10, 0);
        when(flightQueryService.findActive(null)).thenReturn(List.of(
                flightA(dep1, dep1.plusHours(2)),
                flightB(dep2, dep2.plusHours(3))));

        List<AircraftUtilizationDTO> result = service.getUtilizationForAllAircraft();

        assertEquals(2, result.size());
        AircraftUtilizationDTO dtoA = result.stream()
                .filter(d -> d.getRegistrationNumber().equals("CS-TUA"))
                .findFirst().orElseThrow();
        assertEquals("737", dtoA.getModelName());
        assertEquals(1, dtoA.getTotalFlights());
        assertEquals(2.0, dtoA.getTotalFlightHours(), 0.001);
    }

    @Test
    void ensureGetUtilizationForAllAircraftGroupsFlightsByMonthWithinSameAircraft() {
        LocalDateTime dep1 = LocalDateTime.of(2025, 1, 10, 8, 0);
        LocalDateTime dep2 = LocalDateTime.of(2025, 1, 20, 8, 0);
        LocalDateTime dep3 = LocalDateTime.of(2025, 2, 5, 8, 0);
        when(flightQueryService.findActive(null)).thenReturn(List.of(
                flightA(dep1, dep1.plusHours(2)),
                flightA(dep2, dep2.plusHours(3)),
                flightA(dep3, dep3.plusHours(1))));

        List<AircraftUtilizationDTO> result = service.getUtilizationForAllAircraft();

        assertEquals(1, result.size());
        AircraftUtilizationDTO dto = result.get(0);
        assertEquals(2, dto.getUtilizationByPeriod().size());

        assertEquals(2025, dto.getUtilizationByPeriod().get(0).getYear());
        assertEquals(1, dto.getUtilizationByPeriod().get(0).getMonth());
        assertEquals(2, dto.getUtilizationByPeriod().get(0).getTotalFlights());
        assertEquals(5.0, dto.getUtilizationByPeriod().get(0).getTotalFlightHours(), 0.001);

        assertEquals(2025, dto.getUtilizationByPeriod().get(1).getYear());
        assertEquals(2, dto.getUtilizationByPeriod().get(1).getMonth());
        assertEquals(1, dto.getUtilizationByPeriod().get(1).getTotalFlights());
        assertEquals(1.0, dto.getUtilizationByPeriod().get(1).getTotalFlightHours(), 0.001);
    }

    @Test
    void ensureGetUtilizationForAllAircraftSumsTotalsCorrectly() {
        LocalDateTime dep1 = LocalDateTime.of(2025, 3, 1, 8, 0);
        LocalDateTime dep2 = LocalDateTime.of(2025, 3, 15, 8, 0);
        when(flightQueryService.findActive(null)).thenReturn(List.of(
                flightA(dep1, dep1.plusHours(4)),
                flightA(dep2, dep2.plusMinutes(90))));

        List<AircraftUtilizationDTO> result = service.getUtilizationForAllAircraft();

        assertEquals(1, result.size());
        AircraftUtilizationDTO dto = result.get(0);
        assertEquals(2, dto.getTotalFlights());
        assertEquals(5.5, dto.getTotalFlightHours(), 0.001);
    }

    @Test
    void ensureGetUtilizationForAllAircraftHandlesFlightsAcrossMultipleYears() {
        LocalDateTime dep2024 = LocalDateTime.of(2024, 12, 20, 8, 0);
        LocalDateTime dep2025 = LocalDateTime.of(2025, 1, 5, 8, 0);
        when(flightQueryService.findActive(null)).thenReturn(List.of(
                flightA(dep2024, dep2024.plusHours(2)),
                flightA(dep2025, dep2025.plusHours(3))));

        List<AircraftUtilizationDTO> result = service.getUtilizationForAllAircraft();

        assertEquals(1, result.size());
        AircraftUtilizationDTO dto = result.get(0);
        assertEquals(2, dto.getUtilizationByPeriod().size());
        assertEquals(2024, dto.getUtilizationByPeriod().get(0).getYear());
        assertEquals(2025, dto.getUtilizationByPeriod().get(1).getYear());
    }

    @Test
    void ensureGetUtilizationForAircraftReturnsCorrectRegistration() {
        LocalDateTime dep = LocalDateTime.of(2025, 4, 10, 8, 0);
        when(flightQueryService.findActive("CS-TUA")).thenReturn(List.of(flightA(dep, dep.plusHours(2))));

        AircraftUtilizationDTO dto = service.getUtilizationForAircraft("CS-TUA");

        assertEquals("CS-TUA", dto.getRegistrationNumber());
        assertEquals("737", dto.getModelName());
        assertEquals(1, dto.getTotalFlights());
        assertEquals(2.0, dto.getTotalFlightHours(), 0.001);
    }

    @Test
    void ensureGetUtilizationForAircraftNormalizesRegistrationToUpperCase() {
        LocalDateTime dep = LocalDateTime.of(2025, 5, 1, 10, 0);
        when(flightQueryService.findActive("CS-TUA")).thenReturn(List.of(flightA(dep, dep.plusHours(1))));

        AircraftUtilizationDTO dto = service.getUtilizationForAircraft("cs-tua");

        assertEquals("CS-TUA", dto.getRegistrationNumber());
        verify(flightQueryService).findActive("CS-TUA");
    }

    @Test
    void ensureGetUtilizationForAircraftThrowsExceptionWhenNoFlightsExist() {
        when(flightQueryService.findActive("CS-TUA")).thenReturn(List.of());

        assertThrows(ResourceNotFoundException.class, () -> service.getUtilizationForAircraft("CS-TUA"));
    }

    @Test
    void ensureGetUtilizationForAircraftComputesFlightHoursFromDepartureAndArrival() {
        LocalDateTime dep = LocalDateTime.of(2025, 6, 1, 8, 0);
        when(flightQueryService.findActive("CS-TUA")).thenReturn(List.of(flightA(dep, dep.plusMinutes(90))));

        AircraftUtilizationDTO dto = service.getUtilizationForAircraft("CS-TUA");

        assertEquals(1.5, dto.getTotalFlightHours(), 0.001);
    }

    @Test
    void ensureGetUtilizationForAircraftGroupsFlightsByMonth() {
        LocalDateTime dep1 = LocalDateTime.of(2025, 7, 1, 8, 0);
        LocalDateTime dep2 = LocalDateTime.of(2025, 7, 15, 8, 0);
        LocalDateTime dep3 = LocalDateTime.of(2025, 8, 1, 8, 0);
        when(flightQueryService.findActive("CS-TUA")).thenReturn(List.of(
                flightA(dep1, dep1.plusHours(2)),
                flightA(dep2, dep2.plusHours(2)),
                flightA(dep3, dep3.plusHours(3))));

        AircraftUtilizationDTO dto = service.getUtilizationForAircraft("CS-TUA");

        assertEquals(2, dto.getUtilizationByPeriod().size());
        assertEquals(7, dto.getUtilizationByPeriod().get(0).getMonth());
        assertEquals(2, dto.getUtilizationByPeriod().get(0).getTotalFlights());
        assertEquals(4.0, dto.getUtilizationByPeriod().get(0).getTotalFlightHours(), 0.001);
        assertEquals(8, dto.getUtilizationByPeriod().get(1).getMonth());
        assertEquals(1, dto.getUtilizationByPeriod().get(1).getTotalFlights());
        assertEquals(3.0, dto.getUtilizationByPeriod().get(1).getTotalFlightHours(), 0.001);
    }

    @Test
    void ensureQueryIsMadeForTheRequestedAircraftOnly() {
        when(flightQueryService.findActive("CS-TUB")).thenReturn(List.of());

        assertThrows(ResourceNotFoundException.class, () -> service.getUtilizationForAircraft("CS-TUB"));

        verify(flightQueryService, times(1)).findActive("CS-TUB");
        verify(flightQueryService, never()).findActive(null);
    }

    private static FlightView flightA(LocalDateTime departure, LocalDateTime arrival) {
        return flight("CS-TUA", "737", departure, arrival);
    }

    private static FlightView flightB(LocalDateTime departure, LocalDateTime arrival) {
        return flight("CS-TUB", "A320", departure, arrival);
    }

    private static FlightView flight(String reg, String model, LocalDateTime departure, LocalDateTime arrival) {
        return new FlightView(reg + "-" + departure, "route-opo-lis", reg, model, "OPO", "LIS", 277.0, 2.0,
                departure, arrival, "SCHEDULED");
    }
}
