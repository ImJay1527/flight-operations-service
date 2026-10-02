package pt.isep.sidis.flightops.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pt.isep.sidis.flightops.common.security.JwtUtils;

import java.time.Duration;
import java.util.List;

/** HTTP client for the Aircraft & Maintenance service. */
@Component
public class AircraftClient extends ReplicatedServiceClient {

    public AircraftClient(@Value("${flightops.services.aircraft.urls:}") List<String> urls,
                          @Value("${flightops.services.timeout-ms:2000}") long timeoutMs,
                          JwtUtils jwtUtils, RestClient.Builder builder) {
        super("aircraft-maintenance-service", urls.stream().filter(u -> !u.isBlank()).toList(),
                jwtUtils, builder, Duration.ofMillis(timeoutMs));
    }

    public AircraftInfo getAircraft(String registration) {
        return get("/internal/aircraft/{registration}", AircraftInfo.class,
                "Aircraft not found with registration: " + registration, registration);
    }
}
