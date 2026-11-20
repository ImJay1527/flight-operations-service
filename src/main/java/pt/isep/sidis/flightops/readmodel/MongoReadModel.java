package pt.isep.sidis.flightops.readmodel;

import com.mongodb.client.result.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.api.dto.AircraftFuelEfficiencyDTO;
import pt.isep.sidis.flightops.api.dto.AircraftUtilizationDTO;
import pt.isep.sidis.flightops.api.dto.AircraftUtilizationPeriodDTO;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.api.dto.RouteFuelEfficiencyDTO;
import pt.isep.sidis.flightops.common.crypto.FieldEncryption;
import pt.isep.sidis.flightops.domain.FlightStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * The read model in MongoDB, this instance's own read database (separate from its PostgreSQL write database).
 * Three collections, each shaped like the answers it gives: {@code flights}, {@code aircraft_stats},
 * {@code route_stats}. Writes are serialised (one lock): a flight's upsert and the recomputation of its aircraft's
 * and route's reports happen together.
 *
 * <p>A flight is only replaced by a newer state: higher revision, or the same revision and a later status (the rule
 * of the write side, FlightView#newest). The condition is part of the upsert, so it is atomic, and applying the
 * same change twice, or an old change after a newer one, leaves the document as it is (idempotent).
 */
@Component
@ConditionalOnProperty(name = "flightops.read-model.enabled", havingValue = "true")
public class MongoReadModel implements ReadModel {

    static final String FLIGHTS = "flights";
    static final String AIRCRAFT = "aircraft_stats";
    static final String ROUTES = "route_stats";

    private static final Logger log = LoggerFactory.getLogger(MongoReadModel.class);
    private static final String CANCELED = FlightStatus.CANCELED.name();

    private final MongoTemplate mongo;
    private final FieldEncryption crypto;
    private final ReadModelState state = new ReadModelState(ReadModelState.State.BUILDING);
    private final Object writeLock = new Object();

    public MongoReadModel(MongoTemplate mongo, FieldEncryption crypto) {
        this.mongo = mongo;
        this.crypto = crypto;
    }

    @Override
    public ReadModelState state() {
        return state;
    }

    // ------------------------------------------------------------------ writes (projection)

    @Override
    public void project(FlightView flight) {
        synchronized (writeLock) {
            try {
                if (upsert(flight)) {
                    recomputeAircraft(crypto.encrypt(flight.aircraftRegistration()));
                    recomputeRoute(crypto.encrypt(flight.routeId()));
                    log.debug("Read model: flight {} projected (revision {}, {})",
                            flight.flightNumber(), flight.revision(), flight.status());
                }
                state.projected();
            } catch (RuntimeException e) {
                state.stale("read database unavailable (" + e.getClass().getSimpleName() + ")");
                log.debug("Projection of flight {} failed: {}", flight.flightNumber(), e.getMessage());
            }
        }
    }

    /**
     * Brings the read model up to date with these flights (every flight the reachable instances hold) and recomputes
     * every report. If {@code complete} (every instance answered), documents of flights nobody holds any more are
     * removed - except those written after {@code started}, which a projection has just added.
     *
     * @return number of flights that were missing or older here
     */
    int rebuild(Collection<FlightView> flights, boolean complete, Instant started) {
        synchronized (writeLock) {
            ensureIndexes();
            Map<String, FlightView> newest = new LinkedHashMap<>();
            for (FlightView f : flights) {
                newest.merge(f.flightNumber(), f, FlightView::newest);
            }
            int changed = 0;
            for (FlightView f : newest.values()) {
                if (upsert(f)) {
                    changed++;
                }
            }
            if (complete) {
                long removed = mongo.remove(query(where("_id").nin(newest.keySet()).and("projectedAt").lt(started)),
                        FLIGHTS).getDeletedCount();
                if (removed > 0) {
                    log.info("Read model: {} flight(s) no instance holds any more removed", removed);
                }
            }
            recomputeAllReports();
            state.synced();
            return changed;
        }
    }

    /** True if the document was created or replaced by this newer state. */
    private boolean upsert(FlightView f) {
        int rank = FlightStatus.valueOf(f.status()).ordinal();
        Criteria olderThanThis = new Criteria().orOperator(
                where("revision").lt(f.revision()),
                where("revision").is(f.revision()).and("statusRank").lt(rank));
        Update update = new Update()
                .set("routeId", crypto.encrypt(f.routeId()))
                .set("registration", crypto.encrypt(f.aircraftRegistration()))
                .set("model", crypto.encrypt(f.aircraftModel()))
                .set("origin", crypto.encrypt(f.originIata()))
                .set("destination", crypto.encrypt(f.destinationIata()))
                .set("distanceKm", f.distanceKm())
                .set("fuelBurnRate", f.fuelBurnRate())
                .set("departure", f.scheduledDeparture())
                .set("arrival", f.scheduledArrival())
                .set("status", f.status())
                .set("statusRank", rank)
                .set("revision", f.revision())
                .set("projectedAt", Instant.now());
        try {
            UpdateResult result = mongo.upsert(query(where("_id").is(f.flightNumber()).andOperator(olderThanThis)),
                    update, FLIGHTS);
            return result.getModifiedCount() > 0 || result.getUpsertedId() != null;
        } catch (DuplicateKeyException sameOrNewerAlreadyThere) {
            return false;   // the document exists and is not older: nothing to do
        }
    }

    private void recomputeAircraft(String encryptedRegistration) {
        writeAircraftReport(encryptedRegistration,
                mongo.find(query(where("registration").is(encryptedRegistration)), FlightDocument.class, FLIGHTS));
    }

    private void recomputeRoute(String encryptedRouteId) {
        writeRouteReport(encryptedRouteId,
                mongo.find(query(where("routeId").is(encryptedRouteId)), FlightDocument.class, FLIGHTS));
    }

    private void recomputeAllReports() {
        List<FlightDocument> all = mongo.findAll(FlightDocument.class, FLIGHTS);
        Map<String, List<FlightDocument>> byAircraft = all.stream()
                .collect(Collectors.groupingBy(FlightDocument::registration, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<FlightDocument>> byRoute = all.stream()
                .collect(Collectors.groupingBy(FlightDocument::routeId, LinkedHashMap::new, Collectors.toList()));
        byAircraft.forEach(this::writeAircraftReport);
        byRoute.forEach(this::writeRouteReport);
        mongo.remove(query(where("_id").nin(byAircraft.keySet())), AIRCRAFT);
        mongo.remove(query(where("_id").nin(byRoute.keySet())), ROUTES);
    }

    private void writeAircraftReport(String encryptedRegistration, List<FlightDocument> documents) {
        if (documents.isEmpty()) {
            mongo.remove(query(where("_id").is(encryptedRegistration)), AIRCRAFT);
            return;
        }
        List<FlightView> flights = documents.stream().map(this::view).toList();
        List<FlightView> active = flights.stream().filter(f -> !CANCELED.equals(f.status())).toList();
        FlightView any = flights.get(0);
        List<AircraftStatsDocument.Period> periods = List.of();
        double hours = 0;
        double distance = 0;
        double fuel = 0;
        if (!active.isEmpty()) {
            AircraftUtilizationDTO utilization = FlightStatistics.utilization(active).get(0);
            AircraftFuelEfficiencyDTO efficiency = FlightStatistics.aircraftFuel(active).get(0);
            periods = utilization.getUtilizationByPeriod().stream()
                    .map(p -> new AircraftStatsDocument.Period(p.getYear(), p.getMonth(), p.getTotalFlights(),
                            p.getTotalFlightHours()))
                    .toList();
            hours = utilization.getTotalFlightHours();
            distance = efficiency.getTotalDistanceFlownKm();
            fuel = efficiency.getTotalEstimatedFuelL();
        }
        double burnRate = any.fuelBurnRate();
        mongo.save(new AircraftStatsDocument(encryptedRegistration, crypto.encrypt(any.aircraftModel()), burnRate,
                active.size(), flights.size() - active.size(), hours, distance, fuel, burnRate > 0 ? 1.0 / burnRate : 0,
                periods, Instant.now()), AIRCRAFT);
    }

    private void writeRouteReport(String encryptedRouteId, List<FlightDocument> documents) {
        List<FlightView> active = documents.stream().map(this::view)
                .filter(f -> !CANCELED.equals(f.status())).toList();
        if (active.isEmpty()) {
            mongo.remove(query(where("_id").is(encryptedRouteId)), ROUTES);
            return;
        }
        RouteFuelEfficiencyDTO report = FlightStatistics.routeFuel(active).get(0);
        mongo.save(new RouteStatsDocument(encryptedRouteId, crypto.encrypt(report.getOriginIata()),
                crypto.encrypt(report.getDestinationIata()), report.getDistanceKm(), report.getFlightCount(),
                report.getEstimatedFuelPerFlightL(), report.getEfficiencyKmPerL(), Instant.now()), ROUTES);
    }

    private void ensureIndexes() {
        mongo.indexOps(FLIGHTS).ensureIndex(new Index().on("registration", Sort.Direction.ASC).named("by_aircraft"));
        mongo.indexOps(FLIGHTS).ensureIndex(new Index().on("routeId", Sort.Direction.ASC).named("by_route"));
        mongo.indexOps(FLIGHTS).ensureIndex(new Index().on("origin", Sort.Direction.ASC)
                .on("departure", Sort.Direction.ASC).named("departures_board"));
    }

    // ------------------------------------------------------------------ queries

    @Override
    public Optional<FlightView> flight(String flightNumber) {
        return read(() -> Optional.ofNullable(mongo.findById(flightNumber, FlightDocument.class, FLIGHTS)).map(this::view));
    }

    @Override
    public List<FlightView> flightsOfAircraft(String registration) {
        return read(() -> mongo.find(query(where("registration").is(crypto.encrypt(registration)))
                        .with(Sort.by("departure")), FlightDocument.class, FLIGHTS)
                .stream().map(this::view).toList());
    }

    @Override
    public List<FlightView> departures(String originIata, LocalDateTime from, LocalDateTime to) {
        return read(() -> mongo.find(query(where("origin").is(crypto.encrypt(originIata))
                                .and("departure").gte(from).lte(to)
                                .and("status").ne(CANCELED))
                        .with(Sort.by("departure")), FlightDocument.class, FLIGHTS)
                .stream().map(this::view).toList());
    }

    @Override
    public List<AircraftUtilizationDTO> utilization() {
        return read(() -> activeAircraft().map(this::utilizationOf).toList());
    }

    @Override
    public Optional<AircraftUtilizationDTO> utilization(String registration) {
        return read(() -> aircraft(registration).map(this::utilizationOf));
    }

    @Override
    public List<AircraftFuelEfficiencyDTO> aircraftFuel() {
        return read(() -> activeAircraft().map(this::fuelOf).toList());
    }

    @Override
    public Optional<AircraftFuelEfficiencyDTO> aircraftFuel(String registration) {
        return read(() -> aircraft(registration).map(this::fuelOf));
    }

    @Override
    public List<RouteFuelEfficiencyDTO> routeFuel() {
        return read(() -> mongo.findAll(RouteStatsDocument.class, ROUTES).stream()
                .map(this::routeFuelOf)
                .sorted(Comparator.comparing(RouteFuelEfficiencyDTO::getRouteId))
                .toList());
    }

    @Override
    public Optional<RouteFuelEfficiencyDTO> routeFuel(String routeId) {
        return read(() -> Optional.ofNullable(mongo.findById(crypto.encrypt(routeId), RouteStatsDocument.class, ROUTES))
                .map(this::routeFuelOf));
    }

    /** Aircraft with non-cancelled flights, by registration. */
    private java.util.stream.Stream<AircraftStatsDocument> activeAircraft() {
        return mongo.find(query(where("flights").gt(0)), AircraftStatsDocument.class, AIRCRAFT).stream()
                .sorted(Comparator.comparing(d -> crypto.decrypt(d.registration())));
    }

    private Optional<AircraftStatsDocument> aircraft(String registration) {
        return Optional.ofNullable(mongo.findById(crypto.encrypt(registration), AircraftStatsDocument.class, AIRCRAFT))
                .filter(d -> d.flights() > 0);
    }

    private <T> T read(Supplier<T> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            state.stale("read database unavailable (" + e.getClass().getSimpleName() + ")");
            throw new ReadModelUnavailableException("Read database unavailable: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ mapping

    private FlightView view(FlightDocument d) {
        return new FlightView(d.flightNumber(), crypto.decrypt(d.routeId()), crypto.decrypt(d.registration()),
                crypto.decrypt(d.model()), crypto.decrypt(d.origin()), crypto.decrypt(d.destination()), d.distanceKm(),
                d.fuelBurnRate(), d.departure(), d.arrival(), d.status(), d.revision());
    }

    private AircraftUtilizationDTO utilizationOf(AircraftStatsDocument d) {
        return new AircraftUtilizationDTO(crypto.decrypt(d.registration()), crypto.decrypt(d.model()),
                d.periods().stream()
                        .map(p -> new AircraftUtilizationPeriodDTO(p.year(), p.month(), p.flights(), p.hours()))
                        .toList());
    }

    private AircraftFuelEfficiencyDTO fuelOf(AircraftStatsDocument d) {
        return new AircraftFuelEfficiencyDTO(crypto.decrypt(d.registration()), crypto.decrypt(d.model()),
                d.fuelBurnRate(), d.totalDistanceKm(), d.totalFuelL(), d.kmPerL(), d.flights());
    }

    private RouteFuelEfficiencyDTO routeFuelOf(RouteStatsDocument d) {
        return new RouteFuelEfficiencyDTO(crypto.decrypt(d.routeId()), crypto.decrypt(d.origin()),
                crypto.decrypt(d.destination()), d.distanceKm(), d.fuelPerFlightL(), d.kmPerL(), d.flights());
    }

    /** Flight numbers in the read model (for tests and the dashboard). */
    Set<String> flightNumbers() {
        return mongo.findAll(FlightDocument.class, FLIGHTS).stream().map(FlightDocument::flightNumber)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
