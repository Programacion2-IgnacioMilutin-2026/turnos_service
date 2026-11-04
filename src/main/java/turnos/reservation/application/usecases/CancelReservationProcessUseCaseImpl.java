package turnos.reservation.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.reservation.application.exception.ReservationAppointmentNotFoundException;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationInvalidProcessStateException;
import turnos.reservation.application.exception.ReservationLocalStateOutOfSyncException;
import turnos.reservation.application.exception.ReservationNotCancellableException;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.in.CancelReservationProcessUseCase;
import turnos.reservation.domain.ports.out.AppointmentCanceller;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;

import java.time.Clock;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class CancelReservationProcessUseCaseImpl implements CancelReservationProcessUseCase {

    private final ReservationProcessRepository reservationProcessRepository;
    private final AppointmentCanceller appointmentCanceller;
    private final Clock clock;
    private final UserOperationLock userOperationLock;

    // Todo con el candado del usuario, también la espera a la cátedra: un resultado suyo no puede cambiar el proceso
    // mientras se cancela, y un segundo pedido lo encuentra ya cancelado
    @Override
    public ReservationProcess cancelReservationProcess(Long userId, String reservationProcessId, String reason) {
        return userOperationLock.exclusivelyFor(userId, () -> cancel(userId, reservationProcessId, reason));
    }

    private ReservationProcess cancel(Long userId, String reservationProcessId, String reason) {
        ReservationProcess process = reservationProcessRepository.findByIdAndUserId(reservationProcessId, userId)
                .orElseThrow(() -> new ReservationProcessNotFoundException(reservationProcessId));
        if (process.getStatus() == ReservationStatus.CANCELLED) {
            return process;
        }
        if (process.canBeAbandoned()) {
            return abandon(process, reason);
        }
        if (process.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ReservationInvalidProcessStateException(reservationProcessId, process.getStatus());
        }
        Instant cancelledAt = requestCancellation(process, reason);
        process.cancel(cancelledAt, reason);
        return saveCancellation(process);
    }

    // La cátedra no permite cancelarlo, y su hold vence solo: se cierra acá, sin avisarle. Si no se puede guardar, no
    // cambió nada en ningún lado. Del error solo se registra el tipo: su mensaje podría incluir la fila, que puede llevar
    // el teléfono
    private ReservationProcess abandon(ReservationProcess process, String reason) {
        String reservationProcessId = process.getReservationProcessId();
        process.abandon(clock.instant(), reason);
        try {
            return update(process);
        } catch (RuntimeException e) {
            log.error("Abandoned reservation process {} could not be saved: {}", reservationProcessId, e.getClass().getSimpleName());
            throw new ReservationNotSavedException(reservationProcessId, e);
        }
    }

    // En estos errores no se sabe qué tiene la cátedra: el proceso sigue confirmado y queda marcado. Sin integración
    // configurada o con otra cuenta, la cancelación no llegó a aplicarse y no hay nada que reconciliar
    private Instant requestCancellation(ReservationProcess process, String reason) {
        try {
            return appointmentCanceller.cancel(process.getReservationProcessId(), reason);
        } catch (ReservationAppointmentNotFoundException | ReservationNotCancellableException
                 | ReservationCentralUnavailableException | ReservationIntegrationException e) {
            markForReconciliation(process);
            throw e;
        }
    }

    // Si tampoco se puede marcar, el problema que se informa sigue siendo la cátedra
    private void markForReconciliation(ReservationProcess process) {
        process.markForReconciliation();
        try {
            update(process);
        } catch (RuntimeException e) {
            log.error("Reservation process {} could not be marked for reconciliation: {}", process.getReservationProcessId(),
                    e.getClass().getSimpleName());
        }
    }

    // La cátedra ya canceló: si no se puede guardar, se registra para encontrarlo y se intenta al menos marcarlo, con una
    // escritura aparte que no repite la que falló
    private ReservationProcess saveCancellation(ReservationProcess process) {
        String reservationProcessId = process.getReservationProcessId();
        try {
            return update(process);
        } catch (RuntimeException e) {
            log.error("Catedra cancelled reservation process {} but it could not be saved as CANCELLED: {}", reservationProcessId,
                    e.getClass().getSimpleName());
            try {
                reservationProcessRepository.markForReconciliation(reservationProcessId);
            } catch (RuntimeException markFailure) {
                log.error("Reservation process {} could not be marked for reconciliation either: {}", reservationProcessId,
                        markFailure.getClass().getSimpleName());
            }
            throw new ReservationLocalStateOutOfSyncException(reservationProcessId, e);
        }
    }

    private ReservationProcess update(ReservationProcess process) {
        String reservationProcessId = process.getReservationProcessId();
        return reservationProcessRepository.update(reservationProcessId, process)
                .orElseThrow(() -> new IllegalStateException("Reservation process " + reservationProcessId + " disappeared"));
    }
}
