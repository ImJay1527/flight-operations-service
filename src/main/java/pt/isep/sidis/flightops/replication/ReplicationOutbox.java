package pt.isep.sidis.flightops.replication;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.domain.ReplicationTask;
import pt.isep.sidis.flightops.domain.ScheduledFlight;
import pt.isep.sidis.flightops.repositories.ReplicationTaskRepository;

/**
 * Queues a changed flight for the other instances that keep a copy of it (its aircraft's replicas,
 * {@link Cluster#replicasOf}). The task is saved in the caller's transaction, so it exists exactly when the change
 * does; {@link ReplicationSender} delivers it right after the commit, and keeps retrying while an instance is down.
 */
@Service
public class ReplicationOutbox {

    private final ReplicationTaskRepository tasks;
    private final Cluster cluster;
    private final ReplicationSender sender;

    public ReplicationOutbox(ReplicationTaskRepository tasks, Cluster cluster, ReplicationSender sender) {
        this.tasks = tasks;
        this.cluster = cluster;
        this.sender = sender;
    }

    /** Call after saving a new or changed flight, inside the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void flightChanged(ScheduledFlight flight) {
        boolean queued = false;
        for (String instance : cluster.replicasOf(flight.getAircraftRegistration())) {
            if (!instance.equals(cluster.self())) {
                tasks.save(new ReplicationTask(instance, flight.getFlightNumber()));
                queued = true;
            }
        }
        if (queued) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sender.wakeUp();
                }
            });
        }
    }
}
