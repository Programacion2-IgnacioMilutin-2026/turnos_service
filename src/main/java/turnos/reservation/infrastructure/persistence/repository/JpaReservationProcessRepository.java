package turnos.reservation.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;

import java.util.Collection;
import java.util.List;

@Repository
public interface JpaReservationProcessRepository extends JpaRepository<ReservationProcessEntity, String> {

    List<ReservationProcessEntity> findByUserIdAndStatusIn(Long userId, Collection<ReservationStatus> statuses);

}
