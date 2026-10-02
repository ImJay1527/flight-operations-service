package pt.isep.sidis.flightops.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pt.isep.sidis.flightops.common.security.JwtUtils;
import pt.isep.sidis.flightops.resilience.HealthRegistry;
import pt.isep.sidis.flightops.resilience.ResilientCaller;

import java.time.Duration;
import java.util.List;

/** HTTP client for the Aircraft & Maintenance service. */
@Component
@Profile("!stub")   // replaced by built-in data in the "stub" profile
public class AircraftClient extends ReplicatedServiceClient implements AircraftDirectory {

    public AircraftClient(@Value("${flightops.services.aircraft.urls:}") List<String> urls,
                          @Value("${flightops.services.timeout-ms:2000}") long timeoutMs,
                          JwtUtils jwtUtils, RestClient.Builder builder, HttpClientFactory httpClientFactory,
                          HealthRegistry registry, ResilientCaller caller) {
        super("aircraft-maintenance-service", urls.stream().filter(u -> !u.isBlank()).toList(),
                jwtUtils, builder, httpClientFactory, Duration.ofMillis(timeoutMs), registry, caller);
    }

    @Override
    public AircraftInfo getAircraft(String registration) {
        return get("/internal/aircraft/{registration}", AircraftInfo.class,
                "Aircraft not found with registration: " + registration, registration);
    }
}
