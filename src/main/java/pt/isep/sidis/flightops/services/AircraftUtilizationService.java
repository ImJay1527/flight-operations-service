package pt.isep.sidis.flightops.services;

import org.springframework.stereotype.Service;
import pt.isep.sidis.flightops.api.dto.AircraftUtilizationDTO;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.readmodel.FlightStatistics;
import pt.isep.sidis.flightops.readmodel.ReadModel;

import java.util.List;

/**
 * US223 (query side). From the read model, where each aircraft's report is already computed; on the fallback path
 * computed from the flights of all instances.
 */
@Service
public class AircraftUtilizationService {

    private final FlightQueryService writeSide;
    private final ReadModel readModel;
    private final FlightQueries queries;

    public AircraftUtilizationService(FlightQueryService writeSide, ReadModel readModel, FlightQueries queries) {
        this.writeSide = writeSide;
        this.readModel = readModel;
        this.queries = queries;
    }

    public List<AircraftUtilizationDTO> getUtilizationForAllAircraft() {
        return queries.answer(readModel::utilization,
                () -> FlightStatistics.utilization(writeSide.findActive(null)));
    }

    public AircraftUtilizationDTO getUtilizationForAircraft(String registrationNumber) {
        String reg = registrationNumber.toUpperCase();
        return queries.answer(() -> readModel.utilization(reg),
                        () -> FlightStatistics.utilization(writeSide.findActive(reg)).stream().findFirst())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Aircraft with registration " + reg + " not found or has no utilization."));
    }
}
