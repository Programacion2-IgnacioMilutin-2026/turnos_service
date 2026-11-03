package turnos.reservation.domain.ports.in;

import turnos.reservation.domain.model.ReservationProcess;

import java.util.Optional;

public interface GetReservationProcessByIdUseCase {
    // Vacío si no existe o es de otro usuario: los dos casos no se distinguen
    Optional<ReservationProcess> getReservationProcessById(Long userId, String reservationProcessId);
}
