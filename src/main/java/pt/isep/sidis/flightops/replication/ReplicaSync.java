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
import pt.isep.sidis.flightops.peers.PeerClient;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Anti-entropy: asks every peer for the flights this instance should hold and applies the ones it is missing or has
 * an older version of. Runs at startup, before the instance reports ready - so an instance that was down (or is new,
 * or lost its in-memory data) catches up first - and then every {@code flightops.replication.sync-interval-ms}, which
 * repairs anything the outbox could not deliver (e.g. both holders were down at different times).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)   // after the sample data is loaded
public class ReplicaSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReplicaSync.class);

    private final Cluster cluster;
    private final PeerClient peers;
    private final ReplicaStore store;
    private final long intervalMs;
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "replica-sync"));

    public ReplicaSync(Cluster cluster, PeerClient peers, ReplicaStore store,
                       @Value("${flightops.replication.sync-interval-ms:60000}") long intervalMs) {
        this.cluster = cluster;
        this.peers = peers;
        this.store = store;
        this.intervalMs = intervalMs;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!cluster.isClustered() || cluster.replicationFactor() < 2) {
            return;
        }
        syncOnce();
        if (intervalMs > 0) {
            thread.scheduleWithFixedDelay(this::syncOnceSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
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
        return changed;
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
