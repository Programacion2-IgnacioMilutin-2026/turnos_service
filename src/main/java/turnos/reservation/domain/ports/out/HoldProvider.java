package turnos.reservation.domain.ports.out;

import turnos.reservation.domain.model.ReservationHold;

import java.time.LocalDate;
import java.time.LocalTime;

// Si la cátedra no concede el hold, lanza la excepción de reservation que corresponde
public interface HoldProvider {
    ReservationHold requestHold(Long professionalId, LocalDate date, LocalTime startTime, String externalPatientId);
}
