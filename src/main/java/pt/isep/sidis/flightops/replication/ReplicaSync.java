package pt.isep.sidis.flightops.replication;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.domain.ScheduledFlight;
import pt.isep.sidis.flightops.peers.PeerClient;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;
import pt.isep.sidis.flightops.resilience.HealthRegistry;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Anti-entropy: asks every peer for the flights this instance should hold and applies the ones it is missing or has
 * an older version of. Runs at startup, before the instance reports ready - so an instance that was down (or is new,
 * or lost its in-memory data) catches up first - and then every {@code flightops.replication.sync-interval-ms}, which
 * repairs anything the outbox could not deliver (e.g. both holders were down at different times).
 *
 * <p>With discovery (Kubernetes) it also runs shortly after instances join or leave: the aircraft that changed holder
 * are copied to their new holders (rebalancing). It waits {@code flightops.replication.rebalance-delay-ms} so the other
 * instances have seen the change too - they answer with what they think this instance should hold. It also runs
 * {@code flightops.replication.reachable-delay-ms} after a peer answers for the first time or again (a new pod that
 * just became ready, or one that is back): flights waiting to be handed off to it don't wait for the next 60-s run.
 * Flights this instance no longer holds are handed off: sent to their current holders, and deleted here once every
 * holder has them (otherwise this stale copy would still be answered locally). Kept if a holder can't be reached.
 * With a fixed list this happens after the list was changed and the instances restarted (e.g. a third one added).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)   // after the sample data is loaded
public class ReplicaSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReplicaSync.class);

    private final Cluster cluster;
    private final PeerClient peers;
    private final ReplicaStore store;
    private final ScheduledFlightRepository flights;
    private final HealthRegistry healthRegistry;
    private final long intervalMs;
    private final long rebalanceDelayMs;
    private final long reachableDelayMs;
    private final AtomicBoolean syncQueued = new AtomicBoolean();
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "replica-sync"));

    public ReplicaSync(Cluster cluster, PeerClient peers, ReplicaStore store, ScheduledFlightRepository flights,
                       HealthRegistry healthRegistry,
                       @Value("${flightops.replication.sync-interval-ms:60000}") long intervalMs,
                       @Value("${flightops.replication.rebalance-delay-ms:10000}") long rebalanceDelayMs,
                       @Value("${flightops.replication.reachable-delay-ms:1000}") long reachableDelayMs) {
        this.cluster = cluster;
        this.peers = peers;
        this.store = store;
        this.flights = flights;
        this.healthRegistry = healthRegistry;
        this.intervalMs = intervalMs;
        this.rebalanceDelayMs = rebalanceDelayMs;
        this.reachableDelayMs = reachableDelayMs;
    }

    @Override
    public void run(ApplicationArguments args) {
        // with discovery the instance may start alone (factor 1 for now) and get peers later
        if (!cluster.isClustered() || cluster.configuredReplicationFactor() < 2) {
            return;
        }
        syncOnce();
        if (intervalMs > 0) {
            thread.scheduleWithFixedDelay(this::syncOnceSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
        cluster.onChange(change -> {
            log.info("Rebalancing in {} ms: members changed", rebalanceDelayMs);
            thread.schedule(this::syncOnceSafely, rebalanceDelayMs, TimeUnit.MILLISECONDS);
        });
        if (cluster.isDynamic()) {
            healthRegistry.onReachable(endpoint -> {
                if (PeerClient.PEER_GROUP.equals(endpoint.group())) {
                    syncSoon(endpoint.url());
                }
            });
        }
    }

    /** One catch-up + hand-off shortly; several calls in a row cause a single run. */
    private void syncSoon(String reason) {
        if (syncQueued.compareAndSet(false, true)) {
            log.info("Rebalancing in {} ms: {} is reachable", reachableDelayMs, reason);
            thread.schedule(() -> {
                syncQueued.set(false);
                syncOnceSafely();
            }, reachableDelayMs, TimeUnit.MILLISECONDS);
        }
    }

    /** Number of flights added or updated here. */
    public int syncOnce() {
        int changed = 0;
        for (Cluster.Member peer : cluster.peers()) {
            List<FlightView> copies = peers.copiesFor(peer.name(), cluster.self());
            if (copies == null) {
                log.info("Catch-up: {} not reachable, skipped", peer.name());
                continue;
            }
            int fromPeer = 0;
            for (FlightView copy : copies) {
                if (store.apply(copy)) {
                    fromPeer++;
                }
            }
            if (fromPeer > 0) {
                log.info("Catch-up: {} new or updated flight(s) from {} ({} checked)", fromPeer, peer.name(), copies.size());
            }
            changed += fromPeer;
        }
        // also with a fixed list: after it changes (an instance added, restarts) the old holders still have copies
        handOff();
        return changed;
    }

    /** Number of flights handed off to their new holders and deleted here. */
    public int handOff() {
        int handedOff = 0;
        for (ScheduledFlight flight : flights.findAll()) {
            if (cluster.holdsLocally(flight.getAircraftRegistration())) {
                continue;
            }
            FlightView copy = FlightView.of(flight);
            try {
                for (String holder : cluster.replicasOf(flight.getAircraftRegistration())) {
                    peers.pushCopy(holder, copy);
                }
            } catch (RuntimeException e) {
                log.debug("Hand-off of flight {} postponed: {}", flight.getFlightNumber(), e.getMessage());
                continue;   // keep it until every holder has it
            }
            flights.delete(flight);
            handedOff++;
        }
        if (handedOff > 0) {
            log.info("Rebalancing: {} flight(s) handed off to their new holders", handedOff);
        }
        return handedOff;
    }

    private void syncOnceSafely() {
        try {
            syncOnce();
        } catch (RuntimeException e) {
            log.warn("Catch-up failed, will retry: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        thread.shutdownNow();
    }
}
