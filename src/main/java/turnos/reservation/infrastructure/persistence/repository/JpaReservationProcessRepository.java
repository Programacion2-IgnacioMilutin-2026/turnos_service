package turnos.reservation.infrastructure.persistence.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface JpaReservationProcessRepository extends JpaRepository<ReservationProcessEntity, String> {

    // Los procesos del usuario que se pueden listar: un vencido que espera al usuario no, y un cancelado solo si llegó a
    // confirmarse. Es la misma regla de ReservationProcess.hasExpired: el límite ya tiene descontado el margen
    String LISTABLE = """
            p.userId = :userId AND p.status IN :statuses
             AND (p.status NOT IN :waitingForUser OR p.expiresAt >= :expiredBefore)
             AND (p.status <> :cancelled OR p.confirmedAt IS NOT NULL)
            """;
    String NOT_STARTED = "(p.date > :today OR (p.date = :today AND p.startTime >= :currentTime))";

    List<ReservationProcessEntity> findByUserIdAndStatusIn(Long userId, Collection<ReservationStatus> statuses);

    Optional<ReservationProcessEntity> findByReservationProcessIdAndUserId(String reservationProcessId, Long userId);

    @Query("SELECT COUNT(p) FROM ReservationProcessEntity p WHERE " + LISTABLE)
    long countListable(@Param("userId") Long userId, @Param("statuses") Collection<ReservationStatus> statuses,
                       @Param("waitingForUser") Collection<ReservationStatus> waitingForUser,
                       @Param("expiredBefore") Instant expiredBefore, @Param("cancelled") ReservationStatus cancelled);

    // El abierto primero; después los turnos que no comenzaron, del más cercano al más lejano; después los comenzados,
    // del más reciente al más antiguo. El id desempata, para que un proceso no se repita ni falte entre páginas
    @Query("SELECT p FROM ReservationProcessEntity p WHERE " + LISTABLE + """
             ORDER BY CASE WHEN p.status IN :open THEN 0 ELSE 1 END,
                      CASE WHEN """ + NOT_STARTED + """
             THEN 0 ELSE 1 END,
                      CASE WHEN """ + NOT_STARTED + """
             THEN p.date END ASC,
                      CASE WHEN """ + NOT_STARTED + """
             THEN p.startTime END ASC,
                      p.date DESC,
                      p.startTime DESC,
                      p.reservationProcessId ASC
            """)
    List<ReservationProcessEntity> findListable(@Param("userId") Long userId, @Param("statuses") Collection<ReservationStatus> statuses,
                                                @Param("waitingForUser") Collection<ReservationStatus> waitingForUser,
                                                @Param("expiredBefore") Instant expiredBefore,
                                                @Param("cancelled") ReservationStatus cancelled,
                                                @Param("open") Collection<ReservationStatus> open,
                                                @Param("today") LocalDate today, @Param("currentTime") LocalTime currentTime,
                                                Pageable pageable);

    // Es la misma regla de ReservationProcess.needsOverdueResolution: el límite ya tiene descontado el margen. El orden
    // hace que cada barrido recorra los procesos igual
    @Query("""
            SELECT p FROM ReservationProcessEntity p
             WHERE p.expiresAt < :expiredBefore
               AND (p.status IN :waitingForUser OR (p.status = :phoneSubmitted AND p.needsReconciliation = false))
             ORDER BY p.expiresAt ASC, p.reservationProcessId ASC
            """)
    List<ReservationProcessEntity> findOverdue(@Param("expiredBefore") Instant expiredBefore,
                                               @Param("waitingForUser") Collection<ReservationStatus> waitingForUser,
                                               @Param("phoneSubmitted") ReservationStatus phoneSubmitted);

    @Modifying
    @Query("UPDATE ReservationProcessEntity p SET p.needsReconciliation = true WHERE p.reservationProcessId = :id")
    int markForReconciliation(@Param("id") String reservationProcessId);

}
