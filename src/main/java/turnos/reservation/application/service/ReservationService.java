package turnos.reservation.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.ports.in.CreateReservationProcessUseCase;

import java.time.LocalDate;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final CreateReservationProcessUseCase createReservationProcessUseCase;

    public ReservationProcess createReservationProcess(Long userId, Long professionalId, LocalDate date,
                                                       LocalTime startTime, String userToken) {
        return createReservationProcessUseCase.createReservationProcess(userId, professionalId, date, startTime, userToken);
    }
}
