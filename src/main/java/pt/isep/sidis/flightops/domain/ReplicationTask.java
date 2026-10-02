package pt.isep.sidis.flightops.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

/**
 * "Send the current state of this flight to that instance" (transactional outbox). Saved in the same transaction as
 * the change to the flight, so a change is never stored without also being queued for its other copies.
 */
@Entity
@Getter
@Table(name = "replication_outbox", indexes = @Index(columnList = "target"))
public class ReplicationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Instance name, as in flightops.cluster. */
    @Column(nullable = false)
    private String target;

    @Column(nullable = false)
    private String flightNumber;

    @Column(nullable = false)
    private Instant createdAt;

    protected ReplicationTask() {
    }

    public ReplicationTask(String target, String flightNumber) {
        this.target = target;
        this.flightNumber = flightNumber;
        this.createdAt = Instant.now();
    }
}
