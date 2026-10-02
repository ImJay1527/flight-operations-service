package pt.isep.sidis.flightops.clients;

import java.util.List;

/** GET /internal/airports/{iata} of the Airports &amp; Routes service (docs/service-contracts.md). */
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
