package turnos.reservation.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import turnos.reservation.infrastructure.persistence.entity.ProcessedEventEntity;

@Repository
public interface JpaProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {
}
