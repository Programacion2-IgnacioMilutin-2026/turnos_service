package turnos.catedra.domain.ports.in;

import turnos.catedra.domain.model.AppointmentLookupResult;

public interface FindAppointmentUseCase {
    AppointmentLookupResult findAppointment(String externalPatientId, String reservationProcessId);
}
