package turnos.reservation.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ProcessedEvent;
import turnos.reservation.domain.model.ReservationEventOutcome;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationResult;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.in.ProcessReservationResultUseCase;
import turnos.reservation.domain.ports.out.ProcessedEventRepository;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.domain.ports.out.TransactionRunner;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessReservationResultUseCaseImpl implements ProcessReservationResultUseCase {

    private final ReservationProcessRepository reservationProcessRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionRunner transactionRunner;
    private final UserOperationLock userOperationLock;
    private final Clock clock;

    // Con el candado del dueño del proceso: si el resultado llega mientras el usuario opera sobre ese proceso, espera a
    // que la operación termine de guardar, y así ninguna de las dos escrituras pisa a la otra
    @Override
    public ReservationEventOutcome processReservationResult(ReservationResult result) {
        Optional<ReservationProcess> found = reservationProcessRepository.findById(result.reservationProcessId());
        if (found.isEmpty()) {
            if (processedEventRepository.existsById(result.eventId())) {
                return alreadyProcessed(result);
            }
            log.warn("Reservation result {} refers to reservation process {}, which does not exist here",
                    result.eventId(), result.reservationProcessId());
            return ReservationEventOutcome.ORPHAN;
        }
        return userOperationLock.exclusivelyFor(found.get().getUserId(), () -> process(result));
    }

    private ReservationEventOutcome process(ReservationResult result) {
        if (processedEventRepository.existsById(result.eventId())) {
            return alreadyProcessed(result);
        }
        // Se vuelve a leer: mientras esperaba el candado, otra operación del usuario pudo haberlo cambiado
        ReservationProcess process = reservationProcessRepository.findById(result.reservationProcessId()).orElseThrow();
        if (!process.getHoldId().equals(result.holdId())) {
            log.warn("Reservation result {} has hold {}, but reservation process {} has hold {}", result.eventId(),
                    result.holdId(), process.getReservationProcessId(), process.getHoldId());
            return ReservationEventOutcome.HOLD_MISMATCH;
        }
        if (!applies(result, process)) {
            ignore(result, process);
            return ReservationEventOutcome.STATE_ADVANCED;
        }
        apply(result, process);
        // El cambio del proceso y el registro del evento como procesado se guardan juntos o no se guarda ninguno
        transactionRunner.inTransaction(() -> {
            reservationProcessRepository.update(process.getReservationProcessId(), process);
            register(result);
        });
        log.info("Reservation result {} saved: reservation process {} is now {}", result.eventId(),
                process.getReservationProcessId(), process.getStatus());
        return ReservationEventOutcome.APPLIED;
    }

    private static boolean applies(ReservationResult result, ReservationProcess process) {
        return switch (result) {
            case ReservationResult.Confirmed _ -> process.isOpen();
            case ReservationResult.Rejected _ -> process.canBeRejected();
            case ReservationResult.Expired _ -> process.isOpen();
            case ReservationResult.Invalid _ -> process.isOpen();
            case ReservationResult.Cancelled _ -> process.canBeCancelled();
        };
    }

    private void apply(ReservationResult result, ReservationProcess process) {
        switch (result) {
            case ReservationResult.Confirmed confirmed -> {
                warnIfTheAppointmentDiffers(confirmed, process);
                process.confirmReservation(confirmed.reservationId(), confirmed.occurredAt(),
                        confirmed.professionalId(), confirmed.date(), confirmed.startTime());
            }
            case ReservationResult.Rejected rejected -> {
                process.rejectPhone(rejected.reason(), rejected.expiresAt());
                warnIfTheReasonChanged(result, rejected.reason(), process.getRejectionReason());
                // Un cierre posterior borra el motivo: el log es lo único que lo conserva
                log.info("Reservation result {}: phone of reservation process {} rejected with reason {}",
                        result.eventId(), process.getReservationProcessId(), rejected.reason());
            }
            case ReservationResult.Expired _ -> process.closeAsExpired();
            case ReservationResult.Invalid invalid -> {
                process.invalidate();
                log.warn("Reservation result {}: Catedra reported reservation process {} as invalid, with reason {}",
                        result.eventId(), process.getReservationProcessId(), invalid.reason());
            }
            case ReservationResult.Cancelled cancelled -> {
                process.cancel(cancelled.occurredAt(), cancelled.reason());
                warnIfTheReasonChanged(result, cancelled.reason(), process.getCancellationReason());
            }
        }
    }

    // El evento no tiene efecto, pero se registra: la decisión no cambia si llega de nuevo
    private void ignore(ReservationResult result, ReservationProcess process) {
        if (result instanceof ReservationResult.Confirmed confirmed && endedWithoutTheReservation(process)) {
            log.error("Catedra confirmed reservation {} of reservation process {}, which is already {} here (event {})",
                    confirmed.reservationId(), process.getReservationProcessId(), process.getStatus(), result.eventId());
        } else {
            log.info("Reservation result {} ignored: reservation process {} is already {}", result.eventId(),
                    process.getReservationProcessId(), process.getStatus());
        }
        transactionRunner.inTransaction(() -> register(result));
    }

    // La cátedra tiene una reserva que turnos da por cerrada y no puede reabrir
    private static boolean endedWithoutTheReservation(ReservationProcess process) {
        return process.getStatus() == ReservationStatus.EXPIRED || process.getStatus() == ReservationStatus.INVALID;
    }

    private void register(ReservationResult result) {
        processedEventRepository.create(new ProcessedEvent(result.eventId(), clock.instant()));
    }

    private static void warnIfTheAppointmentDiffers(ReservationResult.Confirmed confirmed, ReservationProcess process) {
        boolean differs = differs(confirmed.professionalId(), process.getProfessionalId())
                || differs(confirmed.date(), process.getDate())
                || differs(confirmed.startTime(), process.getStartTime());
        if (differs) {
            log.warn("Reservation result {}: Catedra confirmed professional {} on {} at {}, but reservation process {} "
                            + "had professional {} on {} at {}", confirmed.eventId(), confirmed.professionalId(),
                    confirmed.date(), confirmed.startTime(), process.getReservationProcessId(),
                    process.getProfessionalId(), process.getDate(), process.getStartTime());
        }
    }

    private static boolean differs(Object informed, Object stored) {
        return informed != null && !Objects.equals(informed, stored);
    }

    private static void warnIfTheReasonChanged(ReservationResult result, String received, String stored) {
        if (!Objects.equals(received, stored)) {
            log.warn("Reservation result {}: reason of reservation process {} could not be saved as it came: {}",
                    result.eventId(), result.reservationProcessId(), received);
        }
    }

    private static ReservationEventOutcome alreadyProcessed(ReservationResult result) {
        log.info("Reservation result {} for reservation process {} was already processed", result.eventId(),
                result.reservationProcessId());
        return ReservationEventOutcome.ALREADY_PROCESSED;
    }
}
