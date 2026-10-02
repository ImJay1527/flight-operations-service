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

@Component
@Profile("!stub")   // replaced by built-in data in the "stub" profile
public class AirportsRoutesClient extends ReplicatedServiceClient implements RouteDirectory {

    public AirportsRoutesClient(@Value("${flightops.services.airports-routes.urls:}") List<String> urls,
                                @Value("${flightops.services.timeout-ms:2000}") long timeoutMs,
                                JwtUtils jwtUtils, RestClient.Builder builder, HttpClientFactory httpClientFactory,
                          HealthRegistry registry, ResilientCaller caller) {
        super("airports-routes-service", urls.stream().filter(u -> !u.isBlank()).toList(),
                jwtUtils, builder, httpClientFactory, Duration.ofMillis(timeoutMs), registry, caller);
    }

    @Override
    public RouteInfo getRoute(String routeId) {
        return get("/internal/routes/{routeId}", RouteInfo.class,
                "Flight Route not found with ID: " + routeId, routeId);
    }

    @Override
    public AirportInfo getAirport(String iataCode) {
        return get("/internal/airports/{iata}", AirportInfo.class,
                "Airport not found with IATA code: " + iataCode, iataCode);
    }
}
