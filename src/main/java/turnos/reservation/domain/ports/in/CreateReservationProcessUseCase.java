package turnos.reservation.domain.ports.in;

import turnos.reservation.domain.model.ReservationProcess;

import java.time.LocalDate;
import java.time.LocalTime;

public interface CreateReservationProcessUseCase {
    ReservationProcess createReservationProcess(Long userId, Long professionalId, LocalDate date, LocalTime startTime,
                                                String userToken);
}
