package pt.isep.sidis.flightops.clients;

/**
 * Where this service gets route and airport data from. Normally the Airports &amp; Routes service over HTTP
 * ({@link AirportsRoutesClient}); with the "stub" profile built-in sample data ({@code stub.StubRouteDirectory}).
 */
public interface RouteDirectory {

    /** @throws pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException if it does not exist */
    RouteInfo getRoute(String routeId);

    /** @throws pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException if it does not exist */
    AirportInfo getAirport(String iataCode);
}
