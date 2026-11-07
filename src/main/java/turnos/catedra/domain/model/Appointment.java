package turnos.catedra.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

// Una reserva del listado de la cátedra, solo con lo que turnos usa: sin el nombre ni el teléfono del paciente.
// Tipo inmutable: excepción a la skill autorizada por el usuario. Cualquier dato puede faltar si la cátedra no lo envía
public record Appointment(String reservationProcessId, String externalPatientId, AppointmentStatus status,
                          Long reservationId, Instant confirmedAt, Instant cancelledAt, String cancellationReason,
                          Long professionalId, LocalDate date, LocalTime startTime) {
}
