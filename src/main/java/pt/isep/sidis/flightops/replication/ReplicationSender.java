package pt.isep.sidis.flightops.replication;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.domain.ReplicationTask;
import pt.isep.sidis.flightops.monitoring.FlightOpsMetrics;
import pt.isep.sidis.flightops.peers.PeerClient;
import pt.isep.sidis.flightops.repositories.ReplicationTaskRepository;
import pt.isep.sidis.flightops.repositories.ScheduledFlightRepository;
import pt.isep.sidis.flightops.resilience.CircuitOpenException;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Delivers the outbox ({@link ReplicationOutbox}): sends each queued flight's current state to the instance that
 * keeps a copy, and deletes the task once delivered. Runs right after a change is committed and every
 * {@code flightops.replication.retry-interval-ms}, so a copy for an instance that is down is delivered when it is
 * back. Its own thread: a slow or dead peer never delays requests or health checks.
 */
@Component
public class ReplicationSender {

    private static final Logger log = LoggerFactory.getLogger(ReplicationSender.class);

    private final ReplicationTaskRepository tasks;
    private final ScheduledFlightRepository flights;
    private final PeerClient peers;
    private final FlightOpsMetrics metrics;
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "replication-sender"));
    private final AtomicBoolean wakeUpQueued = new AtomicBoolean();

    public ReplicationSender(ReplicationTaskRepository tasks, ScheduledFlightRepository flights, PeerClient peers,
                             FlightOpsMetrics metrics,
                             @Value("${flightops.replication.retry-interval-ms:1000}") long retryIntervalMs) {
        this.tasks = tasks;
        this.flights = flights;
        this.peers = peers;
        this.metrics = metrics;
        thread.scheduleWithFixedDelay(this::sendPendingSafely, retryIntervalMs, retryIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** Sends as soon as possible (after a commit). Several calls in a row cause a single run. */
    public void wakeUp() {
        if (wakeUpQueued.compareAndSet(false, true)) {
            thread.execute(() -> {
                wakeUpQueued.set(false);
                sendPendingSafely();
            });
        }
    }

    /** Tasks waiting per target instance (shown by GET /api/cluster/health). */
    public Map<String, Long> backlog() {
        Map<String, Long> backlog = new TreeMap<>();
        for (Object[] row : tasks.countByTarget()) {
            backlog.put((String) row[0], (Long) row[1]);
        }
        return backlog;
    }

    private void sendPendingSafely() {
        try {
            sendPending();
        } catch (RuntimeException e) {
            log.warn("Replication run failed, will retry: {}", e.getMessage());
        }
    }

    void sendPending() {
        List<ReplicationTask> batch = tasks.findTop200ByOrderByIdAsc();
        if (batch.isEmpty()) {
            return;
        }
        // target -> flight -> newest task id; a flight changed several times is sent once, in its current state
        Map<String, Map<String, Long>> work = new LinkedHashMap<>();
        for (ReplicationTask task : batch) {
            work.computeIfAbsent(task.getTarget(), t -> new LinkedHashMap<>())
                    .merge(task.getFlightNumber(), task.getId(), Math::max);
        }
        Set<String> unreachable = new HashSet<>();
        work.forEach((target, flightsForTarget) -> flightsForTarget.forEach((flightNumber, upToId) -> {
            if (!unreachable.contains(target) && !deliver(target, flightNumber, upToId)) {
                unreachable.add(target);   // keep its tasks; try the other targets
            }
        }));
        if (batch.size() == 200 && unreachable.size() < work.size()) {
            wakeUp();   // more waiting
        }
    }

    /** False if the target could not be reached (its tasks stay queued). */
    private boolean deliver(String target, String flightNumber, long upToId) {
        FlightView flight = flights.findById(flightNumber).map(FlightView::of).orElse(null);
        if (flight == null) {
            tasks.deleteDelivered(target, flightNumber, upToId);
            return true;
        }
        try {
            peers.pushCopy(target, flight);
            tasks.deleteDelivered(target, flightNumber, upToId);
            metrics.recordReplication("sent");
            log.debug("Copy of flight {} (revision {}) delivered to {}", flightNumber, flight.revision(), target);
            return true;
        } catch (HttpClientErrorException | IllegalArgumentException rejected) {
            // the target refused it, or is no longer in the cluster list: retrying would not help
            log.error("Copy of flight {} for {} dropped: {}", flightNumber, target, rejected.getMessage());
            tasks.deleteDelivered(target, flightNumber, upToId);
            return true;
        } catch (CircuitOpenException | RestClientException down) {
            log.debug("{} unreachable, copy of flight {} stays queued: {}", target, flightNumber, down.getMessage());
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        thread.shutdownNow();
    }
}
