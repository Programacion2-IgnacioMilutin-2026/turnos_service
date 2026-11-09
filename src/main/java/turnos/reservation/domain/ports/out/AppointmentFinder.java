package turnos.reservation.domain.ports.out;

import turnos.reservation.domain.model.ReservationRecord;

import java.util.Optional;

// Vacío si la cátedra no tiene una reserva de ese paciente para el proceso. Si la cátedra no está disponible, la
// integración tiene un error de configuración o la respuesta no se puede usar, lanza la excepción de reservation que corresponde
public interface AppointmentFinder {
    Optional<ReservationRecord> find(String externalPatientId, String reservationProcessId);
}
