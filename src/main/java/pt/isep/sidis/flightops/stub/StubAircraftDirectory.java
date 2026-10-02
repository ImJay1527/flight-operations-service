package pt.isep.sidis.flightops.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.clients.AircraftDirectory;
import pt.isep.sidis.flightops.clients.AircraftInfo;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;

import java.util.Map;

/**
 * "stub" profile only: aircraft data built in, so this service can be run and tested (Postman, PL3 p.16-17) without
 * the Aircraft &amp; Maintenance service. Same data as the shared bootstrap data in docs/service-contracts.md, plus
 * CS-TPM (under maintenance) for the negative tests.
 */
@Component
@Profile("stub")
public class StubAircraftDirectory implements AircraftDirectory {

    private static final Logger log = LoggerFactory.getLogger(StubAircraftDirectory.class);

    private static final Map<String, AircraftInfo> AIRCRAFT = Map.ofEntries(
            aircraft("CS-TPA", "AVAILABLE", "A320neo", 6300.0, 24000.0, 160),
            aircraft("CS-TPB", "AVAILABLE", "737 MAX", 6500.0, 26000.0, 180),
            aircraft("CS-TPC", "AVAILABLE", "A320neo", 6300.0, 24000.0, 160),
            aircraft("CS-TPD", "AVAILABLE", "777X", 8000.0, 35000.0, 400),
            aircraft("CS-TPE", "IN_FLIGHT", "A350", 15000.0, 140000.0, 350),
            aircraft("CS-TPM", "UNDER_MAINTENANCE", "A320neo", 6300.0, 24000.0, 160),
            // extra available aircraft, so that every instance owns some when sharding by registration
            aircraft("CS-TPF", "AVAILABLE", "A320neo", 6300.0, 24000.0, 160),
            aircraft("CS-TPG", "AVAILABLE", "737 MAX", 6500.0, 26000.0, 180),
            aircraft("CS-TPH", "AVAILABLE", "A320neo", 6300.0, 24000.0, 160),
            aircraft("CS-TPI", "AVAILABLE", "737 MAX", 6500.0, 26000.0, 180),
            aircraft("CS-TPJ", "AVAILABLE", "A320neo", 6300.0, 24000.0, 160));

    //                                         registration  status   model   range km  fuel l  seats
    private static Map.Entry<String, AircraftInfo> aircraft(String reg, String status, String model,
                                                            double range, double fuel, int seats) {
        return Map.entry(reg, new AircraftInfo(reg, status, model, range, fuel, seats));
    }

    public StubAircraftDirectory() {
        log.warn("STUB MODE: aircraft data is built in, the Aircraft & Maintenance service is NOT called");
    }

    @Override
    public AircraftInfo getAircraft(String registration) {
        AircraftInfo aircraft = AIRCRAFT.get(registration.toUpperCase());
        if (aircraft == null) {
            throw new ResourceNotFoundException("Aircraft not found with registration: " + registration);
        }
        return aircraft;
    }
}
