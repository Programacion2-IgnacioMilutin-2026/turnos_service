package turnos.reservation.domain.ports.in;

import turnos.reservation.domain.model.PhoneRequestOutcome;
import turnos.reservation.domain.model.ReservationPhoneRequest;

public interface ProcessPhoneRequestUseCase {
    PhoneRequestOutcome processPhoneRequest(ReservationPhoneRequest request);
}
