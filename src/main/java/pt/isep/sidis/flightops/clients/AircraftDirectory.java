package pt.isep.sidis.flightops.clients;

/**
 * Aircraft data: from the Aircraft &amp; Maintenance service ({@link AircraftClient}), or built in with the "stub"
 * profile. With the "amqp" profile the services use {@link LocalAircraftCopy}, which asks one of those two
 * ({@link #REMOTE}) only when its copy can't answer.
 */
public interface AircraftDirectory {

    /** Qualifier of the implementation that asks the source (REST API or built-in data), not the local copy. */
    String REMOTE = "remoteAircraft";

    /** @throws pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException if it does not exist */
    AircraftInfo getAircraft(String registration);
}
