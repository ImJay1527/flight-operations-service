package pt.isep.sidis.flightops.clients;

import java.util.List;

/**
 * Response of the Airports & Routes service: GET /internal/airports/{iata}
 * (also embedded in {@link RouteInfo}). See docs/service-contracts.md.
 */
public record AirportInfo(
        String iataCode,
        String status,                  // OPERATIONAL | CLOSED | UNDER_MAINTENANCE
        List<String> certifiedModels) { // aircraft model names allowed to fly to/from this airport

    public boolean isOperational() {
        return "OPERATIONAL".equals(status);
    }

    public boolean isCertifiedFor(String modelName) {
        return certifiedModels != null && certifiedModels.contains(modelName);
    }
}
