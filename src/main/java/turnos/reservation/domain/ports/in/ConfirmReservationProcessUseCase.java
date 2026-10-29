package turnos.reservation.domain.ports.in;

import turnos.reservation.domain.model.ReservationProcess;

public interface ConfirmReservationProcessUseCase {
    ReservationProcess confirmReservationProcess(Long userId, String reservationProcessId);
}
