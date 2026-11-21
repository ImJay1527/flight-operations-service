package pt.isep.sidis.flightops.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.isep.sidis.flightops.domain.AircraftCopy;

public interface AircraftCopyRepository extends JpaRepository<AircraftCopy, String> {
}
