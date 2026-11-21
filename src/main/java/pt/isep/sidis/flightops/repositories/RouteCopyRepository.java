package pt.isep.sidis.flightops.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.sidis.flightops.domain.RouteCopy;

public interface RouteCopyRepository extends JpaRepository<RouteCopy, String> {
}
