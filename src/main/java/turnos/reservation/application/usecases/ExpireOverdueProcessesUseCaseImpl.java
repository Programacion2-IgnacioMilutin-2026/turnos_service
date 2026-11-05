package turnos.reservation.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.ports.in.ExpireOverdueProcessesUseCase;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExpireOverdueProcessesUseCaseImpl implements ExpireOverdueProcessesUseCase {

    private final ReservationProcessRepository reservationProcessRepository;
    private final ReservationPolicy reservationPolicy;
    private final Clock clock;
    private final UserOperationLock userOperationLock;

    // Sin una transacción para todo el barrido: la falla de un proceso no debe deshacer a los demás, y no conviene
    // mantener una abierta mientras se espera un candado
    @Override
    public void expireOverdueProcesses() {
        Instant startedAt = clock.instant();
        Optional<List<ReservationProcess>> overdue = findOverdue(startedAt);
        if (overdue.isEmpty()) {
            return;
        }
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        for (ReservationProcess process : overdue.get()) {
            outcomes.merge(resolve(process), 1, Integer::sum);
        }
        log.info("Overdue sweep of {} finished: {} closed, {} marked for reconciliation, {} failed", startedAt,
                outcomes.getOrDefault(Outcome.CLOSED, 0), outcomes.getOrDefault(Outcome.MARKED, 0),
                outcomes.getOrDefault(Outcome.FAILED, 0));
    }

    // Una búsqueda fallida termina el barrido sin propagar el error: el siguiente la repite
    private Optional<List<ReservationProcess>> findOverdue(Instant now) {
        try {
            return Optional.of(reservationProcessRepository.findOverdue(now.minus(reservationPolicy.safetyMargin())));
        } catch (RuntimeException e) {
            log.error("Overdue sweep could not search for processes: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // Del error solo se registra el tipo: su mensaje podría incluir la fila, que puede llevar el teléfono. Ese proceso
    // queda como estaba para el barrido siguiente, y se sigue con los demás
    private Outcome resolve(ReservationProcess found) {
        String reservationProcessId = found.getReservationProcessId();
        try {
            return userOperationLock.exclusivelyFor(found.getUserId(), () -> resolveCurrent(reservationProcessId));
        } catch (RuntimeException e) {
            log.error("Overdue reservation process {} could not be resolved: {}", reservationProcessId, e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    // Se vuelve a leer con el candado: desde la búsqueda, un resultado de la cátedra o el usuario pudieron cambiarlo, y
    // guardar lo que se encontró antes lo pisaría
    private Outcome resolveCurrent(String reservationProcessId) {
        Optional<ReservationProcess> current = reservationProcessRepository.findById(reservationProcessId);
        if (current.isEmpty() || !current.get().resolveOverdue(clock.instant(), reservationPolicy.safetyMargin())) {
            return Outcome.UNCHANGED;
        }
        return reservationProcessRepository.update(reservationProcessId, current.get())
                .map(saved -> saved.isOpen() ? Outcome.MARKED : Outcome.CLOSED)
                .orElse(Outcome.UNCHANGED);
    }

    private enum Outcome {
        CLOSED, MARKED, UNCHANGED, FAILED
    }
}
