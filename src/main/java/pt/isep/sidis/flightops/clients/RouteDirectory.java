package pt.isep.sidis.flightops.clients;

/** Route and airport data: from the Airports &amp; Routes service ({@link AirportsRoutesClient}), or built in with the "stub" profile. */
public interface RouteDirectory {

    /** @throws pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException if it does not exist */
    RouteInfo getRoute(String routeId);

    /** @throws pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException if it does not exist */
    AirportInfo getAirport(String iataCode);
}
