package pt.isep.sidis.flightops.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.sidis.flightops.domain.ReplicationTask;

import java.util.List;

public interface ReplicationTaskRepository extends JpaRepository<ReplicationTask, Long> {

    List<ReplicationTask> findTop200ByOrderByIdAsc();

    /**
     * Removes the tasks of one flight for one instance once its state has been delivered. Only up to the last task
     * that was read: a change made while sending has a newer task, which stays and is sent next time.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ReplicationTask t WHERE t.target = :target AND t.flightNumber = :flightNumber AND t.id <= :upToId")
    int deleteDelivered(@Param("target") String target, @Param("flightNumber") String flightNumber,
                        @Param("upToId") long upToId);

    @Query("SELECT t.target, COUNT(t) FROM ReplicationTask t GROUP BY t.target")
    List<Object[]> countByTarget();
}
