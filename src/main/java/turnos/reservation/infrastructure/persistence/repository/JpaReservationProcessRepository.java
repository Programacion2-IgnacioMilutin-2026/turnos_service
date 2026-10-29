package turnos.reservation.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface JpaReservationProcessRepository extends JpaRepository<ReservationProcessEntity, String> {

    List<ReservationProcessEntity> findByUserIdAndStatusIn(Long userId, Collection<ReservationStatus> statuses);

    Optional<ReservationProcessEntity> findByReservationProcessIdAndUserId(String reservationProcessId, Long userId);

    @Modifying
    @Query("UPDATE ReservationProcessEntity p SET p.needsReconciliation = true WHERE p.reservationProcessId = :id")
    int markForReconciliation(@Param("id") String reservationProcessId);

}
