package turnos.reservation.domain.ports.out;

import turnos.reservation.domain.model.ReservationHold;
import turnos.reservation.domain.model.ReservationHoldConfirmation;
import turnos.reservation.domain.model.ReservationPatient;

import java.time.LocalDate;
import java.time.LocalTime;

// Si la cátedra no concede el hold o no acepta la confirmación, lanza la excepción de reservation que corresponde
public interface HoldProvider {
    ReservationHold requestHold(Long professionalId, LocalDate date, LocalTime startTime, String externalPatientId);

    ReservationHoldConfirmation confirmHold(String holdId, Long professionalId, String externalPatientId,
                                            ReservationPatient patient);
}
