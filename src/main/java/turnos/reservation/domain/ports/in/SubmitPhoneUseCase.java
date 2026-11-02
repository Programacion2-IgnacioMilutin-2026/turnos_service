package turnos.reservation.domain.ports.in;

import turnos.reservation.domain.model.ReservationProcess;

public interface SubmitPhoneUseCase {
    ReservationProcess submitPhone(Long userId, String reservationProcessId, String phoneNumber);
}
