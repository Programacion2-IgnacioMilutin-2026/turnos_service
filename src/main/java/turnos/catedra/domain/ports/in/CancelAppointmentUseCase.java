package turnos.catedra.domain.ports.in;

import turnos.catedra.domain.model.AppointmentCancellationResult;

public interface CancelAppointmentUseCase {
    // reason vacío: la cancelación no lleva motivo
    AppointmentCancellationResult cancelAppointment(String reservationProcessId, String reason);
}
