package turnos.catedra.domain.ports.out;

import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.catedra.domain.model.CatedraResponse;

public interface CatedraAppointmentClient {
    CatedraResponse<AppointmentCancellationResult> cancelAppointment(String reservationProcessId, String reason, String technicalToken);
}
