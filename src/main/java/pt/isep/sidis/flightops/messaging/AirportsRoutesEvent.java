package pt.isep.sidis.flightops.messaging;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import pt.isep.sidis.flightops.clients.AirportInfo;
import pt.isep.sidis.flightops.clients.RouteInfo;

import java.time.Instant;
import java.util.List;

/**
 * An event of the Airports &amp; Routes service (docs/service-contracts.md "Events"): only the fields this service
 * uses, the others (and its {@code @type}) are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AirportsRoutesEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String iataCode,
        String status,
        String newStatus,
        List<String> certifiedModels,
        @JsonAlias("aircraftModelName") String modelName,
        String routeId,
        String originIata,
        String destinationIata,
        Double distanceKm,
        Double minRangeRequired,
        Integer minCapacityRequired) {

    public static final String AIRPORT_CREATED = "airport.created";
    /** {@code airport.status}: what the service publishes today; {@code airport.status.changed}: the contract. */
    public static final List<String> AIRPORT_STATUS = List.of("airport.status", "airport.status.changed");
    public static final String AIRPORT_CERTIFIED = "airport.certification.added";
    public static final List<String> ROUTE_CREATED = List.of("route.created", "route.updated");
    public static final String ROUTE_DEACTIVATED = "route.deactivated";

    public boolean isCompleteAirport() {
        return hasText(iataCode);
    }

    public boolean isCompleteStatusChange() {
        return hasText(iataCode) && hasText(newStatus);
    }

    public boolean isCompleteCertification() {
        return hasText(iataCode) && (certifiedModels != null || hasText(modelName));
    }

    public boolean hasRoute() {
        return hasText(routeId);
    }

    /** route.created without what a booking checks (range, seats) can't make a copy: the REST API is used instead. */
    public boolean hasRouteRequirements() {
        return hasText(routeId) && hasText(originIata) && hasText(destinationIata) && distanceKm != null
                && minRangeRequired != null && minCapacityRequired != null;
    }

    public AirportInfo airport() {
        return new AirportInfo(iataCode.toUpperCase(), hasText(status) ? status : "OPERATIONAL",
                certifiedModels == null ? List.of() : certifiedModels);
    }

    /** The route, with its airports by code only (their status and models come from the airports' copies). */
    public RouteInfo route() {
        return new RouteInfo(routeId, hasText(status) ? status : "ACTIVE", distanceKm, minRangeRequired,
                minCapacityRequired, new AirportInfo(originIata.toUpperCase(), null, null),
                new AirportInfo(destinationIata.toUpperCase(), null, null));
    }

    public Instant at() {
        return occurredAt != null ? occurredAt : Instant.now();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
