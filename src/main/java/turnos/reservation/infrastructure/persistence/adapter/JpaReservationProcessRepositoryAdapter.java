package turnos.reservation.infrastructure.persistence.adapter;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.exception.ReservationOpenProcessConflictException;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import turnos.reservation.infrastructure.persistence.repository.JpaReservationProcessRepository;

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
