package pt.isep.sidis.flightops.clients;

/**
 * Where this service gets aircraft data from. Normally the Aircraft &amp; Maintenance service over HTTP
 * ({@link AircraftClient}); with the "stub" profile built-in sample data ({@code stub.StubAircraftDirectory}).
 */
public interface AircraftDirectory {

    /** @throws pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException if it does not exist */
    AircraftInfo getAircraft(String registration);
}
