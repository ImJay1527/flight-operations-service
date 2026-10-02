package pt.isep.sidis.flightops.clients;

/** Aircraft data: from the Aircraft &amp; Maintenance service ({@link AircraftClient}), or built in with the "stub" profile. */
public interface AircraftDirectory {

    /** @throws pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException if it does not exist */
    AircraftInfo getAircraft(String registration);
}
