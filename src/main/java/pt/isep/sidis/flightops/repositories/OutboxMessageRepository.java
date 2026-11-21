package pt.isep.sidis.flightops.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pt.isep.sidis.flightops.domain.OutboxMessage;

import java.time.Instant;
import java.util.List;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    /** The oldest waiting events, in the order the changes were made. */
    List<OutboxMessage> findTop100ByOrderByIdAsc();

    @Query("SELECT MIN(m.createdAt) FROM OutboxMessage m")
    Instant oldestCreatedAt();
}
