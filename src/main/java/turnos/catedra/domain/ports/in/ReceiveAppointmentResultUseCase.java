package turnos.catedra.domain.ports.in;

import turnos.catedra.domain.model.AppointmentResult;

public interface ReceiveAppointmentResultUseCase {
    void receiveAppointmentResult(AppointmentResult result);
}
