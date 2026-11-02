package turnos.reservation.domain.ports.in;

import turnos.reservation.domain.model.ReservationEventOutcome;
import turnos.reservation.domain.model.ReservationPhoneRequest;

public interface ProcessPhoneRequestUseCase {
    ReservationEventOutcome processPhoneRequest(ReservationPhoneRequest request);
}
