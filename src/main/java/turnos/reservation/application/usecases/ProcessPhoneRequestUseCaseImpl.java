package turnos.reservation.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.PhoneRequestOutcome;
import turnos.reservation.domain.model.ProcessedEvent;
import turnos.reservation.domain.model.ReservationPhoneRequest;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.ports.in.ProcessPhoneRequestUseCase;
import turnos.reservation.domain.ports.out.ProcessedEventRepository;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.domain.ports.out.TransactionRunner;

import java.time.Clock;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessPhoneRequestUseCaseImpl implements ProcessPhoneRequestUseCase {

    private final ReservationProcessRepository reservationProcessRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionRunner transactionRunner;
    private final UserOperationLock userOperationLock;
    private final Clock clock;

    // Con el candado del dueño del proceso: si el pedido llega mientras el usuario confirma ese proceso, espera a que
    // la confirmación termine de guardar, y así ninguna de las dos escrituras pisa a la otra.
    // Si la base falla, lanza ReservationNotSavedException sin haber cambiado nada
    @Override
    public PhoneRequestOutcome processPhoneRequest(ReservationPhoneRequest request) {
        Optional<ReservationProcess> found = reservationProcessRepository.findById(request.getReservationProcessId());
        if (found.isEmpty()) {
            if (processedEventRepository.existsById(request.getEventId())) {
                return alreadyProcessed(request);
            }
            log.warn("Phone request {} refers to reservation process {}, which does not exist here",
                    request.getEventId(), request.getReservationProcessId());
            return PhoneRequestOutcome.ORPHAN;
        }
        return userOperationLock.exclusivelyFor(found.get().getUserId(), () -> process(request));
    }

    private PhoneRequestOutcome process(ReservationPhoneRequest request) {
        if (processedEventRepository.existsById(request.getEventId())) {
            return alreadyProcessed(request);
        }
        // Se vuelve a leer: mientras esperaba el candado, otra operación del usuario pudo haberlo cambiado
        ReservationProcess process = reservationProcessRepository.findById(request.getReservationProcessId()).orElseThrow();
        if (!process.getHoldId().equals(request.getHoldId())) {
            log.warn("Phone request {} has hold {}, but reservation process {} has hold {}", request.getEventId(),
                    request.getHoldId(), process.getReservationProcessId(), process.getHoldId());
            return PhoneRequestOutcome.HOLD_MISMATCH;
        }
        if (!process.canAcceptPhoneRequest()) {
            log.info("Phone request {} ignored: reservation process {} is already {}", request.getEventId(),
                    process.getReservationProcessId(), process.getStatus());
            return PhoneRequestOutcome.STATE_ADVANCED;
        }
        process.acceptPhoneRequest(request.getEventId(), request.getExpiresAt());
        // El cambio del proceso y el registro del pedido como procesado se guardan juntos o no se guarda ninguno
        transactionRunner.inTransaction(() -> {
            reservationProcessRepository.update(process.getReservationProcessId(), process);
            processedEventRepository.create(new ProcessedEvent(request.getEventId(), clock.instant()));
        });
        log.info("Phone request {} saved for reservation process {}", request.getEventId(), process.getReservationProcessId());
        return PhoneRequestOutcome.APPLIED;
    }

    private static PhoneRequestOutcome alreadyProcessed(ReservationPhoneRequest request) {
        log.info("Phone request {} for reservation process {} was already processed", request.getEventId(),
                request.getReservationProcessId());
        return PhoneRequestOutcome.ALREADY_PROCESSED;
    }
}
