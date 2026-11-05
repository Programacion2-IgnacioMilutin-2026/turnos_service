package turnos.reservation.domain.ports.out;

import turnos.reservation.domain.model.ReservationListCriteria;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationProcessRepository {

    ReservationProcess create(ReservationProcess reservationProcess);
    Optional<ReservationProcess> update(String reservationProcessId, ReservationProcess reservationProcess);
    List<ReservationProcess> findOpenByUserId(Long userId);

    // Sin controlar el dueño: solo para lo que no llega de parte de un usuario, como los eventos de la cátedra
    Optional<ReservationProcess> findById(String reservationProcessId);

    // Solo si es de ese usuario: un proceso ajeno no se distingue de uno inexistente
    Optional<ReservationProcess> findByIdAndUserId(String reservationProcessId, Long userId);

    // Solo lectura: lo que vencía y no se lista queda como estaba
    ReservationProcessPage findListable(ReservationListCriteria criteria);

    // De todos los usuarios: los que el vencimiento todavía puede cerrar o marcar. Son candidatos: el dato puede cambiar
    // antes de escribir, así que quien los usa vuelve a leerlos
    List<ReservationProcess> findOverdue(Instant expiredBefore);

    // Enciende la marca sin tocar el resto del proceso
    void markForReconciliation(String reservationProcessId);

}
