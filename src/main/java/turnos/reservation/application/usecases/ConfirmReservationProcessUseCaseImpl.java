package turnos.reservation.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationHoldExpiredException;
import turnos.reservation.application.exception.ReservationHoldNotFoundException;
import turnos.reservation.application.exception.ReservationInvalidProcessStateException;
import turnos.reservation.application.exception.ReservationLocalStateOutOfSyncException;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationHoldConfirmation;
import turnos.reservation.domain.model.ReservationPatient;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.in.ConfirmReservationProcessUseCase;
import turnos.reservation.domain.ports.out.HoldProvider;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.domain.ports.out.UserDirectory;

import java.time.Clock;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmReservationProcessUseCaseImpl implements ConfirmReservationProcessUseCase {

    private final ReservationProcessRepository reservationProcessRepository;
    private final UserDirectory userDirectory;
    private final HoldProvider holdProvider;
    private final ReservationPolicy reservationPolicy;
    private final Clock clock;
    private final UserOperationLock userOperationLock;

    // Todo con el candado del usuario: un bloqueo suyo no puede cerrar el proceso mientras se confirma, y una
    // segunda confirmación encuentra el proceso ya confirmado
    @Override
    public ReservationProcess confirmReservationProcess(Long userId, String reservationProcessId) {
        return userOperationLock.exclusivelyFor(userId, () -> confirm(userId, reservationProcessId));
    }

    private ReservationProcess confirm(Long userId, String reservationProcessId) {
        ReservationProcess process = reservationProcessRepository.findByIdAndUserId(reservationProcessId, userId)
                .orElseThrow(() -> new ReservationProcessNotFoundException(reservationProcessId));
        if (process.getStatus() != ReservationStatus.HELD) {
            throw new ReservationInvalidProcessStateException(reservationProcessId, process.getStatus());
        }
        if (process.expireIfOverdue(clock.instant(), reservationPolicy.safetyMargin())) {
            reservationProcessRepository.update(reservationProcessId, process);
            throw new ReservationHoldExpiredException(process.getHoldId());
        }
        // El dueño de un proceso siempre existe (la base lo exige): sin él, el proceso tampoco sería suyo
        ReservationPatient patient = userDirectory.findPatient(userId)
                .orElseThrow(() -> new ReservationProcessNotFoundException(reservationProcessId));
        ReservationHoldConfirmation confirmation = requestConfirmation(process, patient);
        // Si ya estaba confirmado, la cátedra no informa el vencimiento: confirmar no lo cambia, así que sigue el mismo
        process.confirm(confirmation.isAlreadyConfirmed() ? process.getExpiresAt() : confirmation.getExpiresAt());
        return save(process);
    }

    private ReservationHoldConfirmation requestConfirmation(ReservationProcess process, ReservationPatient patient) {
        try {
            return holdProvider.confirmHold(process.getHoldId(), process.getProfessionalId(),
                    process.getUserId().toString(), patient);
        } catch (ReservationHoldExpiredException e) {
            closeAsExpired(process);
            throw e;
        } catch (ReservationHoldNotFoundException e) {
            // Un hold vencido también responde que no existe: pasado su vencimiento, es un hold vencido
            if (process.hasExpired(clock.instant(), Duration.ZERO)) {
                closeAsExpired(process);
                throw new ReservationHoldExpiredException(process.getHoldId());
            }
            throw e;
        } catch (ReservationCentralUnavailableException e) {
            markAfterNoAnswer(process);
            throw e;
        }
    }

    private void closeAsExpired(ReservationProcess process) {
        process.expire();
        reservationProcessRepository.update(process.getReservationProcessId(), process);
    }

    // La cátedra pudo haber confirmado sin que llegara la respuesta: el proceso sigue en HELD y queda marcado.
    // Si tampoco se puede marcar, el problema que se informa sigue siendo la cátedra
    private void markAfterNoAnswer(ReservationProcess process) {
        process.markForReconciliation();
        try {
            reservationProcessRepository.update(process.getReservationProcessId(), process);
        } catch (RuntimeException e) {
            log.error("Reservation process {} could not be marked for reconciliation after Catedra did not answer",
                    process.getReservationProcessId(), e);
        }
    }

    // La cátedra ya confirmó: si no se puede guardar, se registra para encontrarlo y se intenta al menos marcarlo,
    // con una escritura aparte que no repite la que falló
    private ReservationProcess save(ReservationProcess process) {
        String reservationProcessId = process.getReservationProcessId();
        try {
            return reservationProcessRepository.update(reservationProcessId, process)
                    .orElseThrow(() -> new IllegalStateException("Reservation process " + reservationProcessId + " disappeared"));
        } catch (RuntimeException e) {
            log.error("Catedra confirmed reservation process {} (hold {}) but it could not be saved as {}",
                    reservationProcessId, process.getHoldId(), process.getStatus(), e);
            try {
                reservationProcessRepository.markForReconciliation(reservationProcessId);
            } catch (RuntimeException markFailure) {
                log.error("Reservation process {} could not be marked for reconciliation either", reservationProcessId,
                        markFailure);
            }
            throw new ReservationLocalStateOutOfSyncException(reservationProcessId, e);
        }
    }
}
