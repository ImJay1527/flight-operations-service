package pt.isep.sidis.flightops.services;

import org.springframework.stereotype.Service;
import pt.isep.sidis.flightops.api.dto.AircraftUtilizationDTO;
import pt.isep.sidis.flightops.api.dto.AircraftUtilizationPeriodDTO;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** US223, computed over the flights of all instances. */
@Service
public class AircraftUtilizationService {

    private final FlightQueryService flightQueryService;

    public AircraftUtilizationService(FlightQueryService flightQueryService) {
        this.flightQueryService = flightQueryService;
    }

    public List<AircraftUtilizationDTO> getUtilizationForAllAircraft() {
        return buildUtilizationList(flightQueryService.findActive(null));
    }

    public AircraftUtilizationDTO getUtilizationForAircraft(String registrationNumber) {
        String reg = registrationNumber.toUpperCase();
        List<FlightView> flights = flightQueryService.findActive(reg);

        if (flights.isEmpty()) {
            throw new ResourceNotFoundException("Aircraft with registration " + reg + " not found or has no utilization.");
        }
        return buildUtilizationList(flights).get(0);
    }

    private List<AircraftUtilizationDTO> buildUtilizationList(List<FlightView> flights) {
        Map<String, AircraftMeta> aircraftMap = new LinkedHashMap<>();

        for (FlightView sf : flights) {
            String reg = sf.aircraftRegistration();
            AircraftMeta meta = aircraftMap.computeIfAbsent(reg, k -> new AircraftMeta(reg, sf.aircraftModel()));

            int year = sf.scheduledDeparture().getYear();
            int month = sf.scheduledDeparture().getMonthValue();
            String periodKey = year + "-" + String.format("%02d", month);

            meta.addFlight(periodKey, year, month, computeFlightHours(sf));
        }

        List<AircraftUtilizationDTO> result = new ArrayList<>();
        for (AircraftMeta meta : aircraftMap.values()) {
            result.add(meta.toDTO());
        }
        return result;
    }

    private double computeFlightHours(FlightView sf) {
        long minutes = ChronoUnit.MINUTES.between(sf.scheduledDeparture(), sf.scheduledArrival());
        return minutes / 60.0;
    }

    private static class AircraftMeta {
        private final String registrationNumber;
        private final String modelName;
        private final Map<String, PeriodAccumulator> periods = new LinkedHashMap<>();

        AircraftMeta(String registrationNumber, String modelName) {
            this.registrationNumber = registrationNumber;
            this.modelName = modelName;
        }

        void addFlight(String periodKey, int year, int month, double hours) {
            periods.computeIfAbsent(periodKey, k -> new PeriodAccumulator(year, month)).add(hours);
        }

        AircraftUtilizationDTO toDTO() {
            List<AircraftUtilizationPeriodDTO> periodList = new ArrayList<>();
            for (PeriodAccumulator acc : periods.values()) {
                periodList.add(new AircraftUtilizationPeriodDTO(acc.year, acc.month, acc.totalFlights, acc.totalHours));
            }
            return new AircraftUtilizationDTO(registrationNumber, modelName, periodList);
        }
    }

    private static class PeriodAccumulator {
        final int year;
        final int month;
        long totalFlights = 0;
        double totalHours = 0.0;

        PeriodAccumulator(int year, int month) {
            this.year = year;
            this.month = month;
        }

        void add(double hours) {
            totalFlights++;
            totalHours += hours;
        }
    }
}
