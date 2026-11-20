package pt.isep.sidis.flightops.readmodel;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.peers.PeerClient;
import pt.isep.sidis.flightops.peers.PeerResult;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;
import pt.isep.sidis.flightops.resilience.HealthRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds and repairs the read model from the write side: this instance's flights plus every peer's
 * ({@code GET /internal/flights/all}). The projections keep it up to date between runs; this is the safety net
 * for anything they missed (read database down for a while, an instance that was down, a lost event).
 *
 * <p>Runs at startup - before the instance reports ready, so it never answers from an empty read model - then
 * every {@code flightops.read-model.sync-interval-ms}, every {@code retry-interval-ms} while the model is not READY,
 * and shortly after a peer becomes reachable. The result is READY if every flight was seen: with each flight on
 * {@code replicationFactor} instances, that is the case unless at least that many peers did not answer (the rule of
 * {@link PeerResult#mayHideIt}); otherwise STALE, and queries keep using the write side.
 */
@Component
@ConditionalOnProperty(name = "flightops.read-model.enabled", havingValue = "true")
@Order(Ordered.LOWEST_PRECEDENCE)   // after the sample data and the replication catch-up
public class ReadModelSync implements ApplicationRunner {

    static final String ALL_FLIGHTS = "/internal/flights/all";
    private static final Logger log = LoggerFactory.getLogger(ReadModelSync.class);
    private static final ParameterizedTypeReference<List<FlightView>> FLIGHT_LIST = new ParameterizedTypeReference<>() {};

    private final MongoReadModel readModel;
    private final ScheduledFlightRepository writeSide;
    private final PeerClient peers;
    private final Cluster cluster;
    private final HealthRegistry healthRegistry;
    private final long syncIntervalMs;
    private final long retryIntervalMs;
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "read-model-sync"));
    private final AtomicBoolean syncQueued = new AtomicBoolean();

    public ReadModelSync(MongoReadModel readModel, ScheduledFlightRepository writeSide, PeerClient peers,
                         Cluster cluster, HealthRegistry healthRegistry,
                         @Value("${flightops.read-model.sync-interval-ms:30000}") long syncIntervalMs,
                         @Value("${flightops.read-model.retry-interval-ms:5000}") long retryIntervalMs) {
        this.readModel = readModel;
        this.writeSide = writeSide;
        this.peers = peers;
        this.cluster = cluster;
        this.healthRegistry = healthRegistry;
        this.syncIntervalMs = syncIntervalMs;
        this.retryIntervalMs = retryIntervalMs;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncOnce();
        thread.scheduleWithFixedDelay(this::tick, retryIntervalMs, retryIntervalMs, TimeUnit.MILLISECONDS);
        healthRegistry.onReachable(endpoint -> {
            if (PeerClient.PEER_GROUP.equals(endpoint.group())) {
                syncSoon();
            }
        });
    }

    /** Every retry interval: sync if the model is not READY, or if the sync interval has passed. */
    private void tick() {
        Instant last = readModel.state().lastSync();
        boolean due = last == null || last.plusMillis(syncIntervalMs).isBefore(Instant.now());
        if (!readModel.isReady() || due) {
            syncOnce();
        }
    }

    /** A sync shortly (a peer is back); several calls in a row cause a single run. */
    public void syncSoon() {
        if (syncQueued.compareAndSet(false, true)) {
            thread.schedule(() -> {
                syncQueued.set(false);
                syncOnce();
            }, 1, TimeUnit.SECONDS);
        }
    }

    /** One sync; never throws (a failure makes the model STALE). */
    public synchronized void syncOnce() {
        Instant started = Instant.now();
        try {
            List<FlightView> flights = new ArrayList<>(writeSide.findAll().stream().map(FlightView::of).toList());
            PeerResult<FlightView> remote = peers.getList(ALL_FLIGHTS, FLIGHT_LIST);
            flights.addAll(remote.items());
            int changed = readModel.rebuild(flights, remote.unreachable() == 0, started);
            if (remote.mayHideIt(cluster.replicationFactor())) {
                readModel.state().stale(remote.unreachable() + " instance(s) unreachable during the last sync");
            } else {
                readModel.state().ready();
            }
            if (changed > 0) {
                log.info("Read model sync: {} flight(s) were missing or older ({} checked, {} unreachable peer(s))",
                        changed, flights.size(), remote.unreachable());
            }
        } catch (RuntimeException e) {
            readModel.state().stale("read database unavailable (" + e.getClass().getSimpleName() + ")");
            log.debug("Read model sync failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        thread.shutdownNow();
    }
}
