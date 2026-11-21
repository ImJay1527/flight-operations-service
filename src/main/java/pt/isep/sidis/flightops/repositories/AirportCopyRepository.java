package pt.isep.sidis.flightops.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.sidis.flightops.domain.AirportCopy;

public interface AirportCopyRepository extends JpaRepository<AirportCopy, String> {
}
