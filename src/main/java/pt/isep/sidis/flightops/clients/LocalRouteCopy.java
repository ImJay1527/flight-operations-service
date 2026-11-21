package pt.isep.sidis.flightops.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import pt.isep.sidis.flightops.common.exceptions.ServiceUnavailableException;
import pt.isep.sidis.flightops.common.metrics.FlightOpsMetrics;
import pt.isep.sidis.flightops.domain.AirportCopy;
import pt.isep.sidis.flightops.domain.RouteCopy;
import pt.isep.sidis.flightops.repositories.AirportCopyRepository;
import pt.isep.sidis.flightops.repositories.RouteCopyRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Routes and airports from this instance's own copies (tables route_copy, airport_copy) instead of a REST call to
 * the Airports &amp; Routes service on every booking. Same rules as {@link LocalAircraftCopy}: with the "amqp"
 * profile only, kept up to date by that service's events (airport.created, airport.status(.changed),
 * airport.certification.added, route.created, route.updated, route.deactivated); what is not in the copy, or hasn't
 * changed for {@code copy-max-age-ms}, is read from the REST API ({@code remote}) and kept; with the REST API down
 * the copy is used anyway.
 *
 * <p>A route is answered from the copy only when the route and both its airports are there.
 */
@Component
@Primary
@ConditionalOnProperty(name = "flightops.messaging.enabled", havingValue = "true")
public class LocalRouteCopy implements RouteDirectory {

    private static final Logger log = LoggerFactory.getLogger(LocalRouteCopy.class);

    private final RouteCopyRepository routes;
    private final AirportCopyRepository airports;
    private final RouteDirectory remote;
    private final TransactionTemplate ownTransaction;
    private final FlightOpsMetrics metrics;
    private final Duration maxAge;

    public LocalRouteCopy(RouteCopyRepository routes, AirportCopyRepository airports,
                          @Qualifier(RouteDirectory.REMOTE) RouteDirectory remote,
                          PlatformTransactionManager transactions, FlightOpsMetrics metrics,
                          @Value("${flightops.messaging.copy-max-age-ms:3600000}") long maxAgeMs) {
        this.routes = routes;
        this.airports = airports;
        this.remote = remote;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.metrics = metrics;
        this.maxAge = Duration.ofMillis(maxAgeMs);
    }

    @Override
    public RouteInfo getRoute(String routeId) {
        Optional<RouteCopy> route = routes.findById(routeId);
        Optional<AirportCopy> origin = route.flatMap(r -> airports.findById(r.getOriginIata()));
        Optional<AirportCopy> destination = route.flatMap(r -> airports.findById(r.getDestinationIata()));
        boolean complete = route.isPresent() && origin.isPresent() && destination.isPresent();
        Instant now = Instant.now();
        if (complete && !route.get().olderThan(maxAge, now) && !origin.get().olderThan(maxAge, now)
                && !destination.get().olderThan(maxAge, now)) {
            metrics.recordRouteLookup("route", "copy");
            return info(route.get(), origin.get(), destination.get());
        }
        RouteInfo fresh;
        try {
            fresh = remote.getRoute(routeId);   // 404 if that service doesn't know it
        } catch (ServiceUnavailableException e) {
            if (!complete) {
                throw e;
            }
            metrics.recordRouteLookup("route", "old_copy");
            log.warn("Route {}: {}; using the copy of {}", routeId, e.getMessage(), route.get().getAsOf());
            return info(route.get(), origin.get(), destination.get());
        }
        metrics.recordRouteLookup("route", complete ? "refreshed" : "rest");
        Instant read = Instant.now();
        keepRoute(fresh, read, "rest");
        keepAirport(fresh.origin(), read, "rest");
        keepAirport(fresh.destination(), read, "rest");
        return fresh;
    }

    @Override
    public AirportInfo getAirport(String iataCode) {
        Optional<AirportCopy> airport = airports.findById(iataCode.toUpperCase());
        if (airport.isPresent() && !airport.get().olderThan(maxAge, Instant.now())) {
            metrics.recordRouteLookup("airport", "copy");
            return info(airport.get());
        }
        AirportInfo fresh;
        try {
            fresh = remote.getAirport(iataCode);
        } catch (ServiceUnavailableException e) {
            if (airport.isEmpty()) {
                throw e;
            }
            metrics.recordRouteLookup("airport", "old_copy");
            return info(airport.get());
        }
        metrics.recordRouteLookup("airport", airport.isPresent() ? "refreshed" : "rest");
        keepAirport(fresh, Instant.now(), "rest");
        return fresh;
    }

    // ------------------------------------------------------------------ events

    /** airport.created: the whole airport. False if the copy is newer. */
    public boolean airportCreated(AirportInfo airport, Instant at) {
        return keepAirport(airport, at, "event");
    }

    /** airport.status(.changed). False if the copy is newer or the airport isn't in it (read from REST when needed). */
    public boolean airportStatusChanged(String iataCode, String newStatus, Instant at) {
        return change(airports::findById, iataCode.toUpperCase(), a -> a.changeStatus(newStatus, at), airports::save);
    }

    /** airport.certification.added: the whole list, or the one model added. False as above. */
    public boolean airportCertified(String iataCode, List<String> allModels, String addedModel, Instant at) {
        return change(airports::findById, iataCode.toUpperCase(), a -> a.certify(allModels, addedModel, at),
                airports::save);
    }

    /** route.created / route.updated with the route's requirements. False if the copy is newer. */
    public boolean routeCreated(RouteInfo route, Instant at) {
        return keepRoute(route, at, "event");
    }

    /** route.deactivated. False if the copy is newer or the route isn't in it. */
    public boolean routeDeactivated(String routeId, Instant at) {
        return change(routes::findById, routeId, r -> r.changeStatus("DEACTIVATED", at), routes::save);
    }

    // ------------------------------------------------------------------ writes, each in a transaction of its own

    private <T> boolean change(Function<String, Optional<T>> find, String id, Function<T, Boolean> change,
                               Function<T, T> save) {
        return Boolean.TRUE.equals(ownTransaction.execute(tx -> {
            Optional<T> copy = find.apply(id);
            if (copy.isEmpty() || !change.apply(copy.get())) {
                return false;
            }
            save.apply(copy.get());
            return true;
        }));
    }

    private boolean keepRoute(RouteInfo r, Instant at, String source) {
        if (r.origin() == null || r.destination() == null) {
            return false;
        }
        return keep(source, r.routeId(), () -> {
            RouteCopy copy = routes.findById(r.routeId()).orElseGet(() -> new RouteCopy(r.routeId()));
            if (!copy.replace(r.status(), r.distanceKm(), r.minRangeRequired(), r.minCapacityRequired(),
                    r.origin().iataCode(), r.destination().iataCode(), at, source)) {
                return false;
            }
            routes.save(copy);
            return true;
        });
    }

    private boolean keepAirport(AirportInfo a, Instant at, String source) {
        if (a == null || a.iataCode() == null) {
            return false;
        }
        return keep(source, a.iataCode(), () -> {
            AirportCopy copy = airports.findById(a.iataCode().toUpperCase())
                    .orElseGet(() -> new AirportCopy(a.iataCode()));
            if (!copy.replace(a.status(), a.certifiedModels(), at, source)) {
                return false;
            }
            airports.save(copy);
            return true;
        });
    }

    /** An event that collides with another writer is retried by the listener; a REST read is simply not kept. */
    private boolean keep(String source, String id, Supplier<Boolean> write) {
        try {
            return Boolean.TRUE.equals(ownTransaction.execute(tx -> write.get()));
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            if ("event".equals(source)) {
                throw e;
            }
            log.debug("{} changed while it was read from the REST API: not kept", id);
            return false;
        }
    }

    private static RouteInfo info(RouteCopy r, AirportCopy origin, AirportCopy destination) {
        return new RouteInfo(r.getRouteId(), r.getStatus(), r.getDistanceKm(), r.getMinRangeRequired(),
                r.getMinCapacityRequired(), info(origin), info(destination));
    }

    private static AirportInfo info(AirportCopy a) {
        return new AirportInfo(a.getIataCode(), a.getStatus(), a.getCertifiedModels());
    }
}
