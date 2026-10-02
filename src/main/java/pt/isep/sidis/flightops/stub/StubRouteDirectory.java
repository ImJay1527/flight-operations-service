package pt.isep.sidis.flightops.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.clients.AirportInfo;
import pt.isep.sidis.flightops.clients.RouteDirectory;
import pt.isep.sidis.flightops.clients.RouteInfo;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;

import java.util.List;
import java.util.Map;

/**
 * "stub" profile only: route and airport data built in, so this service can be run and tested without the
 * Airports &amp; Routes service. Same data as docs/service-contracts.md, plus route-opo-mad-old (deactivated)
 * for the negative tests.
 */
@Component
@Profile("stub")
public class StubRouteDirectory implements RouteDirectory {

    private static final Logger log = LoggerFactory.getLogger(StubRouteDirectory.class);

    private static final List<String> CERTIFIED = List.of("A320neo", "737 MAX");   // 777X / A350 not certified

    private static final Map<String, AirportInfo> AIRPORTS = Map.of(
            "OPO", new AirportInfo("OPO", "OPERATIONAL", CERTIFIED),
            "LIS", new AirportInfo("LIS", "OPERATIONAL", CERTIFIED),
            "MAD", new AirportInfo("MAD", "OPERATIONAL", CERTIFIED));

    //                                          id                status         km     min range  min seats
    private static final Map<String, RouteInfo> ROUTES = Map.of(
            "route-opo-lis", route("route-opo-lis", "ACTIVE", 277.0, 350.0, 100, "OPO", "LIS"),
            "route-lis-mad", route("route-lis-mad", "ACTIVE", 502.0, 600.0, 150, "LIS", "MAD"),
            "route-mad-opo", route("route-mad-opo", "ACTIVE", 420.0, 500.0, 120, "MAD", "OPO"),
            "route-opo-mad-old", route("route-opo-mad-old", "DEACTIVATED", 420.0, 500.0, 120, "OPO", "MAD"));

    public StubRouteDirectory() {
        log.warn("STUB MODE: route/airport data is built in, the Airports & Routes service is NOT called");
    }

    private static RouteInfo route(String id, String status, double km, double minRange, int minSeats,
                                   String origin, String destination) {
        return new RouteInfo(id, status, km, minRange, minSeats, AIRPORTS.get(origin), AIRPORTS.get(destination));
    }

    @Override
    public RouteInfo getRoute(String routeId) {
        RouteInfo route = ROUTES.get(routeId);
        if (route == null) {
            throw new ResourceNotFoundException("Flight Route not found with ID: " + routeId);
        }
        return route;
    }

    @Override
    public AirportInfo getAirport(String iataCode) {
        AirportInfo airport = AIRPORTS.get(iataCode.toUpperCase());
        if (airport == null) {
            throw new ResourceNotFoundException("Airport not found with IATA code: " + iataCode);
        }
        return airport;
    }
}
