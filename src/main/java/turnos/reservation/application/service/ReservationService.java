package turnos.reservation.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import turnos.reservation.domain.model.PhoneRequestOutcome;
import turnos.reservation.domain.model.ReservationPhoneRequest;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.ports.in.ConfirmReservationProcessUseCase;
import turnos.reservation.domain.ports.in.CreateReservationProcessUseCase;
import turnos.reservation.domain.ports.in.ProcessPhoneRequestUseCase;

import java.time.LocalDate;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final CreateReservationProcessUseCase createReservationProcessUseCase;
    private final ConfirmReservationProcessUseCase confirmReservationProcessUseCase;
    private final ProcessPhoneRequestUseCase processPhoneRequestUseCase;

    public ReservationProcess createReservationProcess(Long userId, Long professionalId, LocalDate date,
                                                       LocalTime startTime, String userToken) {
        return createReservationProcessUseCase.createReservationProcess(userId, professionalId, date, startTime, userToken);
    }

    public ReservationProcess confirmReservationProcess(Long userId, String reservationProcessId) {
        return confirmReservationProcessUseCase.confirmReservationProcess(userId, reservationProcessId);
    }

    public PhoneRequestOutcome processPhoneRequest(ReservationPhoneRequest request) {
        return processPhoneRequestUseCase.processPhoneRequest(request);
    }
}
