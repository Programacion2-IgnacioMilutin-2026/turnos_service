package turnos.catedra.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

// El resultado que informa la cátedra sobre un proceso de reserva, ya validado. Cada variante tiene solo sus datos.
// Tipos inmutables: excepción a la skill autorizada por el usuario
public sealed interface AppointmentResult {

    String eventId();

    String reservationProcessId();

    String holdId();

    // professionalId, date y startTime pueden faltar
    record Confirmed(String eventId, String reservationProcessId, String holdId, Long reservationId, Instant occurredAt,
                     Long professionalId, LocalDate date, LocalTime startTime) implements AppointmentResult {
    }

    record Rejected(String eventId, String reservationProcessId, String holdId, String reason,
                    Instant expiresAt) implements AppointmentResult {
    }

    record Expired(String eventId, String reservationProcessId, String holdId) implements AppointmentResult {
    }

    // reason describe la inconsistencia que detectó la cátedra; puede faltar
    record Invalid(String eventId, String reservationProcessId, String holdId,
                   String reason) implements AppointmentResult {
    }

    // reason puede faltar
    record Cancelled(String eventId, String reservationProcessId, String holdId, Instant occurredAt,
                     String reason) implements AppointmentResult {
    }
}
