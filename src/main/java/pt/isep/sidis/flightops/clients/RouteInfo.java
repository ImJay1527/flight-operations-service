package pt.isep.sidis.flightops.clients;

/**
 * Response of the Airports & Routes service: GET /internal/routes/{routeId}.
 * See docs/service-contracts.md.
 */
public record RouteInfo(
        String routeId,
        String status,              // ACTIVE | DEACTIVATED
        double distanceKm,
        double minRangeRequired,
        int minCapacityRequired,
        AirportInfo origin,
        AirportInfo destination) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
