package pt.isep.sidis.flightops.clients;

/** GET /internal/aircraft/{registration} of the Aircraft &amp; Maintenance service (docs/service-contracts.md). */
public record AircraftInfo(
        String registrationNumber,
        String status,          // AVAILABLE | IN_FLIGHT | UNDER_MAINTENANCE | INACTIVE
        String modelName,
        double maxRange,        // km
        double fuelCapacity,    // litres
        int activeCapacity) {   // seats in the aircraft's current configuration

    public boolean isAvailable() {
        return "AVAILABLE".equals(status);
    }

    public double fuelBurnRate() {
        return fuelCapacity / maxRange;
    }
}
