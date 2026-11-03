package turnos.reservation.domain.ports.in;

import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationStatus;

public interface FindReservationProcessesByUserUseCase {
    // status vacío: todas las que se pueden listar
    ReservationProcessPage findReservationProcessesByUser(Long userId, ReservationStatus status, int page, int size);
}
