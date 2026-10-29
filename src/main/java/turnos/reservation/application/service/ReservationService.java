package turnos.reservation.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.ports.in.ConfirmReservationProcessUseCase;
import turnos.reservation.domain.ports.in.CreateReservationProcessUseCase;

import java.time.LocalDate;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final CreateReservationProcessUseCase createReservationProcessUseCase;
    private final ConfirmReservationProcessUseCase confirmReservationProcessUseCase;

    public ReservationProcess createReservationProcess(Long userId, Long professionalId, LocalDate date,
                                                       LocalTime startTime, String userToken) {
        return createReservationProcessUseCase.createReservationProcess(userId, professionalId, date, startTime, userToken);
    }

    public ReservationProcess confirmReservationProcess(Long userId, String reservationProcessId) {
        return confirmReservationProcessUseCase.confirmReservationProcess(userId, reservationProcessId);
    }
}
