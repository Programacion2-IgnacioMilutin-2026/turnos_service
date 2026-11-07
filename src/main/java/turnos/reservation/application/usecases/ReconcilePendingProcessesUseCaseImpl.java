package turnos.reservation.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReconciliationOutcome;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationRecord;
import turnos.reservation.domain.model.ReservationRecordStatus;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.in.ReconcilePendingProcessesUseCase;
import turnos.reservation.domain.ports.out.AppointmentFinder;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcilePendingProcessesUseCaseImpl implements ReconcilePendingProcessesUseCase {

    private final ReservationProcessRepository reservationProcessRepository;
    private final AppointmentFinder appointmentFinder;
    private final ReservationPolicy reservationPolicy;
    private final Clock clock;
    private final UserOperationLock userOperationLock;
    // Los abiertos sin marca se revisan en la primera ejecución, la del arranque, y en las siguientes hasta un ciclo en
    // que la cátedra contestó por todos. Vive en memoria: un reinicio dispara otra ejecución de arranque
    private final AtomicBoolean openReviewPending = new AtomicBoolean(true);

    // Sin una transacción para todo el ciclo: la falla de un proceso no debe deshacer a los demás, y nada queda abierto
    // mientras se espera a la cátedra o un candado
    @Override
    public void reconcilePendingProcesses() {
        Instant startedAt = clock.instant();
        boolean includeOpen = openReviewPending.get();
        Optional<List<ReservationProcess>> pending = findPending(includeOpen);
        if (pending.isEmpty()) {
            return;
        }
        List<ReservationProcess> processes = pending.get();
        Map<Result, Integer> results = new EnumMap<>(Result.class);
        for (int index = 0; index < processes.size(); index++) {
            try {
                results.merge(reconcile(processes.get(index)), 1, Integer::sum);
            } catch (ReservationCentralUnavailableException | ReservationServiceDegradedException e) {
                // Si la cátedra no contesta por uno, tampoco lo hará por los demás: se deja todo para el ciclo siguiente
                int left = processes.size() - index;
                log.warn("Reconciliation of {} stopped, {} reservation processes left for the next cycle: {}",
                        startedAt, left, e.getMessage());
                results.merge(Result.LEFT, left, Integer::sum);
                break;
            }
        }
        if (includeOpen && !results.containsKey(Result.LEFT)) {
            openReviewPending.set(false);
        }
        log.info("Reconciliation of {} finished: {} corrected, {} already matched, {} still pending, {} unresolved, "
                        + "{} discarded, {} failed, {} left for the next cycle", startedAt,
                count(results, Result.CORRECTED), count(results, Result.MATCHED), count(results, Result.STILL_PENDING),
                count(results, Result.UNRESOLVED), count(results, Result.DISCARDED), count(results, Result.FAILED),
                count(results, Result.LEFT));
    }

    // Una búsqueda fallida termina el ciclo sin propagar el error: el siguiente la repite, también la de los abiertos
    private Optional<List<ReservationProcess>> findPending(boolean includeOpen) {
        try {
            return Optional.of(reservationProcessRepository.findToReconcile(includeOpen));
        } catch (RuntimeException e) {
            log.error("Reconciliation could not search for processes: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // La cátedra se consulta sin el candado del usuario: ni él ni los eventos de Kafka esperan por esto. Un problema de
    // este proceso no detiene a los demás; uno de la cátedra, sí, y sale de aquí
    private Result reconcile(ReservationProcess found) {
        String reservationProcessId = found.getReservationProcessId();
        if (!found.canBeReconciled()) {
            log.error("Reservation process {} cannot be reconciled: it is {} and marked", reservationProcessId, found.getStatus());
            return Result.FAILED;
        }
        Optional<ReservationRecord> record;
        try {
            record = appointmentFinder.find(found.getUserId().toString(), reservationProcessId);
        } catch (ReservationCentralUnavailableException | ReservationServiceDegradedException e) {
            throw e;
        } catch (ReservationIntegrationException e) {
            log.error("Reservation process {} could not be checked with Catedra: {}", reservationProcessId, e.getMessage());
            return Result.FAILED;
        } catch (RuntimeException e) {
            log.error("Reservation process {} could not be checked with Catedra: {}", reservationProcessId, e.getClass().getSimpleName());
            return Result.FAILED;
        }
        // Del error solo se registra el tipo: su mensaje podría incluir la fila, que puede llevar el teléfono
        try {
            return userOperationLock.exclusivelyFor(found.getUserId(), () -> applyToCurrent(found, record));
        } catch (RuntimeException e) {
            log.error("Reservation process {} could not be reconciled: {}", reservationProcessId, e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    // Se vuelve a leer con el candado: si un evento o el usuario lo cambiaron mientras se consultaba a la cátedra, la
    // respuesta puede ser vieja y no se aplica. Si sigue marcado, lo toma el ciclo siguiente
    private Result applyToCurrent(ReservationProcess found, Optional<ReservationRecord> record) {
        String reservationProcessId = found.getReservationProcessId();
        Optional<ReservationProcess> current = reservationProcessRepository.findById(reservationProcessId);
        if (current.isEmpty() || current.get().getStatus() != found.getStatus()
                || current.get().isNeedsReconciliation() != found.isNeedsReconciliation()) {
            log.info("Reconciliation of reservation process {} discarded: it changed while Catedra was consulted",
                    reservationProcessId);
            return Result.DISCARDED;
        }
        ReservationProcess process = current.get();
        ReservationStatus previousStatus = process.getStatus();
        boolean previousMark = process.isNeedsReconciliation();
        Long previousReservationId = process.getReservationId();
        boolean appointmentDiffers = record.isPresent() && appointmentDiffers(record.get(), process);
        ReconciliationOutcome outcome = record.isPresent()
                ? process.reconcileWith(record.get())
                : process.reconcileWithoutRecord(clock.instant(), reservationPolicy.safetyMargin());
        boolean changed = process.getStatus() != previousStatus || process.isNeedsReconciliation() != previousMark
                || !Objects.equals(process.getReservationId(), previousReservationId);
        if (changed) {
            reservationProcessRepository.update(reservationProcessId, process);
        }
        logOutcome(process, previousStatus, record, outcome, appointmentDiffers);
        return Result.of(outcome);
    }

    // Solo cuenta si la confirmación llega ahora: un proceso que ya estaba confirmado conserva sus datos
    private static boolean appointmentDiffers(ReservationRecord record, ReservationProcess process) {
        return record.status() == ReservationRecordStatus.CONFIRMED && process.getStatus() != ReservationStatus.CONFIRMED
                && (differs(record.professionalId(), process.getProfessionalId())
                || differs(record.date(), process.getDate())
                || differs(record.startTime(), process.getStartTime()));
    }

    private static boolean differs(Object informed, Object stored) {
        return informed != null && !Objects.equals(informed, stored);
    }

    private static void logOutcome(ReservationProcess process, ReservationStatus previousStatus,
                                   Optional<ReservationRecord> record, ReconciliationOutcome outcome, boolean appointmentDiffers) {
        String reservationProcessId = process.getReservationProcessId();
        String catedra = record.map(found -> found.status().name()).orElse("no record");
        if (appointmentDiffers) {
            log.warn("Reservation process {}: Catedra confirmed a different professional, date or time than the one stored here; "
                    + "the one of Catedra was kept", reservationProcessId);
        }
        switch (outcome) {
            case CORRECTED -> log.info("Reservation process {} reconciled with Catedra ({}): {} is now {}",
                    reservationProcessId, catedra, previousStatus, process.getStatus());
            case MATCHED -> log.info("Reservation process {} already matched Catedra ({}): it stays {}",
                    reservationProcessId, catedra, process.getStatus());
            case STILL_PENDING -> log.info("Reservation process {} stays {}: Catedra has not resolved the submitted phone yet",
                    reservationProcessId, process.getStatus());
            case UNRESOLVED -> log.error("Reservation process {} is {} here, but Catedra has {}; it cannot be corrected "
                    + "without reopening it", reservationProcessId, process.getStatus(), catedra);
        }
    }

    private static int count(Map<Result, Integer> results, Result result) {
        return results.getOrDefault(result, 0);
    }

    private enum Result {
        CORRECTED, MATCHED, STILL_PENDING, UNRESOLVED, DISCARDED, FAILED, LEFT;

        static Result of(ReconciliationOutcome outcome) {
            return switch (outcome) {
                case CORRECTED -> CORRECTED;
                case MATCHED -> MATCHED;
                case STILL_PENDING -> STILL_PENDING;
                case UNRESOLVED -> UNRESOLVED;
            };
        }
    }
}
