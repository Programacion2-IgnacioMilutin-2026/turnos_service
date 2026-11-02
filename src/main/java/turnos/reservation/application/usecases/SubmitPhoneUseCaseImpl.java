package turnos.reservation.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationHoldExpiredException;
import turnos.reservation.application.exception.ReservationInvalidPhoneNumberException;
import turnos.reservation.application.exception.ReservationInvalidProcessStateException;
import turnos.reservation.application.exception.ReservationLocalStateOutOfSyncException;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.exception.ReservationPhoneRequestNotReadyException;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationPhoneNumber;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.ports.in.SubmitPhoneUseCase;
import turnos.reservation.domain.ports.out.PhoneSubmitter;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;

import java.time.Clock;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubmitPhoneUseCaseImpl implements SubmitPhoneUseCase {

    private final ReservationProcessRepository reservationProcessRepository;
    private final PhoneSubmitter phoneSubmitter;
    private final ReservationPolicy reservationPolicy;
    private final Clock clock;
    private final UserOperationLock userOperationLock;

    // El formato no necesita la base: un número inválido no gasta una lectura ni espera el candado.
    // Todo lo demás, también publicar, con el candado del usuario: un resultado de la cátedra espera a que esto termine,
    // y nadie se mete en la mitad si hay que volver atrás
    @Override
    public ReservationProcess submitPhone(Long userId, String reservationProcessId, String phoneNumber) {
        ReservationPhoneNumber phone = ReservationPhoneNumber.parse(phoneNumber)
                .orElseThrow(ReservationInvalidPhoneNumberException::new);
        return userOperationLock.exclusivelyFor(userId, () -> submit(userId, reservationProcessId, phone));
    }

    private ReservationProcess submit(Long userId, String reservationProcessId, ReservationPhoneNumber phone) {
        ReservationProcess process = reservationProcessRepository.findByIdAndUserId(reservationProcessId, userId)
                .orElseThrow(() -> new ReservationProcessNotFoundException(reservationProcessId));
        if (!process.canSubmitPhone()) {
            throw new ReservationInvalidProcessStateException(reservationProcessId, process.getStatus());
        }
        if (process.expireIfOverdue(clock.instant(), reservationPolicy.safetyMargin())) {
            reservationProcessRepository.update(reservationProcessId, process);
            throw new ReservationHoldExpiredException(process.getHoldId());
        }
        if (process.getRequestEventId() == null) {
            throw new ReservationPhoneRequestNotReadyException(reservationProcessId);
        }
        if (!phoneSubmitter.canSubmit()) {
            throw new ReservationCentralUnavailableException("Technical integration is not available");
        }
        process.submitPhone(phone);
        ReservationProcess saved = save(process);
        try {
            phoneSubmitter.submit(process);
            return saved;
        } catch (RuntimeException e) {
            goBackToWaitingForThePhone(process);
            throw e;
        }
    }

    // Se guarda antes de publicar: si esto falla, no salió nada y el proceso queda como estaba.
    // Del error solo se registra el tipo: su mensaje podría incluir la fila, que lleva el teléfono
    private ReservationProcess save(ReservationProcess process) {
        String reservationProcessId = process.getReservationProcessId();
        try {
            return reservationProcessRepository.update(reservationProcessId, process)
                    .orElseThrow(() -> new IllegalStateException("Reservation process " + reservationProcessId + " disappeared"));
        } catch (RuntimeException e) {
            log.error("Phone of reservation process {} could not be saved, nothing was published: {}", reservationProcessId,
                    e.getClass().getSimpleName());
            throw new ReservationNotSavedException(reservationProcessId, e);
        }
    }

    // El teléfono no salió, pero el proceso figura como enviado: si tampoco se puede corregir, queda así hasta la
    // reconciliación, y hay que poder encontrarlo
    private void goBackToWaitingForThePhone(ReservationProcess process) {
        String reservationProcessId = process.getReservationProcessId();
        process.revertPhoneSubmission();
        try {
            reservationProcessRepository.update(reservationProcessId, process);
        } catch (RuntimeException e) {
            log.error("Reservation process {} (hold {}) stays as PHONE_SUBMITTED although its phone was not published, because it could not be saved: {}",
                    reservationProcessId, process.getHoldId(), e.getClass().getSimpleName());
            throw new ReservationLocalStateOutOfSyncException(reservationProcessId, e);
        }
    }
}
