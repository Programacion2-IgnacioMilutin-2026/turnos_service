package turnos.reservation.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.ports.in.GetReservationProcessByIdUseCase;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;

import java.time.Clock;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetReservationProcessByIdUseCaseImpl implements GetReservationProcessByIdUseCase {

    private final ReservationProcessRepository reservationProcessRepository;
    private final ReservationPolicy reservationPolicy;
    private final Clock clock;
    private final UserOperationLock userOperationLock;

    // A diferencia de las demás operaciones, solo toma el candado si tiene que escribir: la app repite esta consulta cada
    // pocos segundos, y no debería esperar a que otra operación del usuario hable con la cátedra
    @Override
    public Optional<ReservationProcess> getReservationProcessById(Long userId, String reservationProcessId) {
        Optional<ReservationProcess> found = reservationProcessRepository.findByIdAndUserId(reservationProcessId, userId);
        if (found.isEmpty() || !found.get().needsOverdueResolution(clock.instant(), reservationPolicy.safetyMargin())) {
            return found;
        }
        return userOperationLock.exclusivelyFor(userId, () -> resolveOverdue(userId, reservationProcessId));
    }

    // Se vuelve a leer con el candado: mientras se esperaba, un resultado de la cátedra pudo cerrar el proceso, y guardar
    // lo leído antes lo pisaría
    private Optional<ReservationProcess> resolveOverdue(Long userId, String reservationProcessId) {
        Optional<ReservationProcess> current = reservationProcessRepository.findByIdAndUserId(reservationProcessId, userId);
        if (current.isEmpty() || !current.get().resolveOverdue(clock.instant(), reservationPolicy.safetyMargin())) {
            return current;
        }
        return Optional.of(save(current.get()));
    }

    // Del error solo se registra el tipo: su mensaje podría incluir la fila, que puede llevar el teléfono.
    // Si falla, el proceso queda como estaba y la próxima consulta lo vuelve a intentar
    private ReservationProcess save(ReservationProcess process) {
        String reservationProcessId = process.getReservationProcessId();
        try {
            ReservationProcess saved = reservationProcessRepository.update(reservationProcessId, process)
                    .orElseThrow(() -> new IllegalStateException("Reservation process " + reservationProcessId + " disappeared"));
            log.info("Overdue reservation process {} resolved: it is {}, marked for reconciliation: {}", reservationProcessId,
                    saved.getStatus(), saved.isNeedsReconciliation());
            return saved;
        } catch (RuntimeException e) {
            log.error("Overdue reservation process {} could not be saved: {}", reservationProcessId, e.getClass().getSimpleName());
            throw new ReservationNotSavedException(reservationProcessId, e);
        }
    }
}
