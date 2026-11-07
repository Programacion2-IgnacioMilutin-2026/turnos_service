package turnos.catedra.domain.ports.out;

import turnos.catedra.domain.model.Appointment;
import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.catedra.domain.model.CatedraResponse;

import java.util.List;

public interface CatedraAppointmentClient {
    CatedraResponse<AppointmentCancellationResult> cancelAppointment(String reservationProcessId, String reason, String technicalToken);

    // Una página de las reservas de un paciente, sin filtrar por fecha; page empieza en 0
    CatedraResponse<List<Appointment>> listAppointments(String externalPatientId, int page, int size, String technicalToken);
}
