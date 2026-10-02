package pt.isep.sidis.flightops.clients;

/** GET /internal/routes/{routeId} of the Airports &amp; Routes service (docs/service-contracts.md). */
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
