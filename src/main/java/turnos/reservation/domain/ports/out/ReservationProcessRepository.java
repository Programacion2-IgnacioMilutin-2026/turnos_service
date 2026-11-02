package turnos.reservation.domain.ports.out;

import turnos.reservation.domain.model.ReservationProcess;

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

    // Enciende la marca sin tocar el resto del proceso
    void markForReconciliation(String reservationProcessId);

}
