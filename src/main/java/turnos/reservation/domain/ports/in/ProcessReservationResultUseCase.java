package turnos.reservation.domain.ports.in;

import turnos.reservation.domain.model.ReservationEventOutcome;
import turnos.reservation.domain.model.ReservationResult;

public interface ProcessReservationResultUseCase {
    ReservationEventOutcome processReservationResult(ReservationResult result);
}
