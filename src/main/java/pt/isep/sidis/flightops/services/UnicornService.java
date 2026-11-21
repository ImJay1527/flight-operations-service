package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.api.dto.UnicornDTO;
import pt.isep.sidis.flightops.clients.AircraftDirectory;
import pt.isep.sidis.flightops.clients.AircraftInfo;
import pt.isep.sidis.flightops.readmodel.ReadModel;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * GET /api/v2/unicorns: the airplanes' moods. A query of CQRS: it only reads the read model (every flight of the
 * cluster, on this instance) and never changes anything. The mood is worked out when asked ({@link UnicornMood}),
 * because it depends on the time ("in the air now", "leaves within 24 h"). The read model is kept up to date by the
 * commands' events, so a booking or a completion changes the mood. Without a usable read model the write side
 * answers (as for every query).
 */
@Service
@RequiredArgsConstructor
public class UnicornService {

    private final ReadModel readModel;
    private final FlightQueryService writeSide;
    private final FlightQueries queries;
    private final AircraftDirectory aircraftClient;

    /** Every airplane with flights, by registration. */
    public List<UnicornDTO> all() {
        LocalDateTime now = LocalDateTime.now();
        List<FlightView> flights = queries.answer(readModel::activeFlights, () -> writeSide.findActive(null));
        Map<String, List<FlightView>> byAircraft = flights.stream()
                .collect(Collectors.groupingBy(FlightView::aircraftRegistration, TreeMap::new, Collectors.toList()));
        return byAircraft.entrySet().stream()
                .map(e -> UnicornMood.of(e.getKey(), e.getValue().get(0).aircraftModel(), e.getValue(), now))
                .toList();
    }

    /** One airplane, also without flights (BORED); 404 if Aircraft & Maintenance doesn't know it. */
    public UnicornDTO of(String registration) {
        AircraftInfo aircraft = aircraftClient.getAircraft(registration);
        String reg = aircraft.registrationNumber();
        List<FlightView> flights = queries.answer(() -> readModel.flightsOfAircraft(reg),
                () -> writeSide.findByAircraft(reg));
        return UnicornMood.of(reg, aircraft.modelName(), flights, LocalDateTime.now());
    }
}
