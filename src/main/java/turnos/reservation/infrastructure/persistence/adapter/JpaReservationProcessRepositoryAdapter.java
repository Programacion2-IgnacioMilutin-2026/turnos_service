package turnos.reservation.infrastructure.persistence.adapter;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.exception.ReservationOpenProcessConflictException;
import turnos.reservation.domain.model.ReservationListCriteria;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import turnos.reservation.infrastructure.persistence.repository.JpaReservationProcessRepository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaReservationProcessRepositoryAdapter implements ReservationProcessRepository {

    // El índice de la base que impide dos procesos abiertos del mismo usuario
    private static final String ONE_OPEN_PER_USER = "ux_reservation_processes_one_open_per_user";
    private static final List<ReservationStatus> OPEN_STATUSES =
            Arrays.stream(ReservationStatus.values()).filter(ReservationStatus::isOpen).toList();
    private static final List<ReservationStatus> LISTABLE_STATUSES =
            Arrays.stream(ReservationStatus.values()).filter(ReservationStatus::isListable).toList();
    private static final List<ReservationStatus> WAITING_FOR_USER_STATUSES =
            List.of(ReservationStatus.HELD, ReservationStatus.WAITING_FOR_PHONE);

    private final JpaReservationProcessRepository jpaReservationProcessRepository;
    private final ReservationProcessMapper reservationProcessMapper;

    // Se guarda con flush para que un rechazo de la base ocurra acá y se traduzca: el índice de un proceso abierto
    // por usuario es un conflicto; cualquier otra falla (por ejemplo, un usuario inexistente) es que no se pudo guardar
    @Override
    public ReservationProcess create(ReservationProcess reservationProcess) {
        ReservationProcessEntity entity = reservationProcessMapper.toEntity(reservationProcess);
        try {
            return reservationProcessMapper.toDomainModel(jpaReservationProcessRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            if (violates(e, ONE_OPEN_PER_USER)) {
                throw new ReservationOpenProcessConflictException(reservationProcess.getUserId());
            }
            throw new ReservationNotSavedException(reservationProcess.getReservationProcessId(), e);
        } catch (DataAccessException e) {
            throw new ReservationNotSavedException(reservationProcess.getReservationProcessId(), e);
        }
    }

    // En una sola transacción: la lectura deja la entidad en memoria y guardar solo agrega el UPDATE. El flush lo
    // manda enseguida: si no, dentro de una transacción mayor Hibernate haría antes los INSERT pendientes, y un proceso
    // nuevo chocaría con el índice antes de que el viejo dejara de estar abierto. Actualizar sin proceso es un error de
    // programación: falla al entrar, antes de leer la base
    @Override
    @Transactional
    public Optional<ReservationProcess> update(String reservationProcessId, ReservationProcess reservationProcess) {
        Objects.requireNonNull(reservationProcess, "reservationProcess must not be null");
        if (jpaReservationProcessRepository.findById(reservationProcessId).isEmpty()) {
            return Optional.empty();
        }
        ReservationProcessEntity entity = reservationProcessMapper.toEntity(reservationProcess);
        entity.setReservationProcessId(reservationProcessId);
        entity.markAsExisting();
        return Optional.of(reservationProcessMapper.toDomainModel(jpaReservationProcessRepository.saveAndFlush(entity)));
    }

    @Override
    public List<ReservationProcess> findOpenByUserId(Long userId) {
        return jpaReservationProcessRepository.findByUserIdAndStatusIn(userId, OPEN_STATUSES).stream()
                .map(reservationProcessMapper::toDomainModel)
                .toList();
    }

    // Una base caída se informa igual que al guardar: un evento de la cátedra que no se pudo leer se reintenta, no se descarta
    @Override
    public Optional<ReservationProcess> findById(String reservationProcessId) {
        try {
            return jpaReservationProcessRepository.findById(reservationProcessId).map(reservationProcessMapper::toDomainModel);
        } catch (DataAccessException | TransactionException e) {
            throw new ReservationNotSavedException(reservationProcessId, e);
        }
    }

    @Override
    public Optional<ReservationProcess> findByIdAndUserId(String reservationProcessId, Long userId) {
        return jpaReservationProcessRepository.findByReservationProcessIdAndUserId(reservationProcessId, userId)
                .map(reservationProcessMapper::toDomainModel);
    }

    // Cuenta primero: una página más allá del total ya se sabe vacía, y un número de página enorme no entraría en el
    // desplazamiento de la consulta, que la base limita a un int. Una falla al leer llega sin traducir
    @Override
    public ReservationProcessPage findListable(ReservationListCriteria criteria) {
        List<ReservationStatus> statuses = criteria.status() != null ? List.of(criteria.status()) : LISTABLE_STATUSES;
        long total = jpaReservationProcessRepository.countListable(criteria.userId(), statuses, WAITING_FOR_USER_STATUSES,
                criteria.expiredBefore(), ReservationStatus.CANCELLED);
        if ((long) criteria.page() * criteria.size() >= total) {
            return new ReservationProcessPage(List.of(), total);
        }
        List<ReservationProcess> items = jpaReservationProcessRepository.findListable(criteria.userId(), statuses,
                        WAITING_FOR_USER_STATUSES, criteria.expiredBefore(), ReservationStatus.CANCELLED, OPEN_STATUSES,
                        criteria.today(), criteria.currentTime(), PageRequest.of(criteria.page(), criteria.size())).stream()
                .map(reservationProcessMapper::toDomainModel)
                .toList();
        return new ReservationProcessPage(items, total);
    }

    // La falla no es de un proceso en particular: se traduce igual que una lectura por id
    @Override
    public List<ReservationProcess> findOverdue(Instant expiredBefore) {
        try {
            return jpaReservationProcessRepository.findOverdue(expiredBefore, WAITING_FOR_USER_STATUSES, ReservationStatus.PHONE_SUBMITTED).stream()
                    .map(reservationProcessMapper::toDomainModel)
                    .toList();
        } catch (DataAccessException | TransactionException e) {
            throw new ReservationNotSavedException(e);
        }
    }

    // Como la búsqueda de vencidos: la falla no es de un proceso en particular
    @Override
    public List<ReservationProcess> findToReconcile(boolean includeOpen) {
        try {
            return jpaReservationProcessRepository.findToReconcile(includeOpen, OPEN_STATUSES).stream()
                    .map(reservationProcessMapper::toDomainModel)
                    .toList();
        } catch (DataAccessException | TransactionException e) {
            throw new ReservationNotSavedException(e);
        }
    }

    // Un UPDATE de una sola columna: si guardar el proceso completo acaba de fallar, esto no repite esa escritura
    @Override
    @Transactional
    public void markForReconciliation(String reservationProcessId) {
        try {
            jpaReservationProcessRepository.markForReconciliation(reservationProcessId);
        } catch (DataAccessException e) {
            throw new ReservationNotSavedException(reservationProcessId, e);
        }
    }

    private static boolean violates(Throwable error, String constraint) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
