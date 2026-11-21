package pt.isep.sidis.flightops.clients;

/**
 * Route and airport data: from the Airports &amp; Routes service ({@link AirportsRoutesClient}), or built in with the
 * "stub" profile. With the "amqp" profile the services use {@link LocalRouteCopy}, which asks one of those two
 * ({@link #REMOTE}) only when its copy can't answer.
 */
public interface RouteDirectory {

    /** Qualifier of the implementation that asks the source (REST API or built-in data), not the local copy. */
    String REMOTE = "remoteRoutes";

    /** @throws pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException if it does not exist */
    RouteInfo getRoute(String routeId);

    /** @throws pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException if it does not exist */
    AirportInfo getAirport(String iataCode);
}
