package turnos.reservation.domain.ports.in;

import turnos.reservation.domain.model.ReservationProcess;

public interface CancelReservationProcessUseCase {
    // reason vacío: la cancelación no lleva motivo
    ReservationProcess cancelReservationProcess(Long userId, String reservationProcessId, String reason);
}
