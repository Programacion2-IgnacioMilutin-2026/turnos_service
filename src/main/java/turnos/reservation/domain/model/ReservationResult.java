package turnos.reservation.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

// El resultado que informó la cátedra sobre un proceso. Copia propia de reservation: el slice no depende del modelo de
// catedra. Cada variante tiene solo sus datos. Tipos inmutables: excepción a la skill autorizada por el usuario
public sealed interface ReservationResult {

    String eventId();

    String reservationProcessId();

    String holdId();

    // professionalId, date y startTime pueden faltar: quedan los guardados
    record Confirmed(String eventId, String reservationProcessId, String holdId, Long reservationId, Instant occurredAt,
                     Long professionalId, LocalDate date, LocalTime startTime) implements ReservationResult {
    }

    record Rejected(String eventId, String reservationProcessId, String holdId, String reason,
                    Instant expiresAt) implements ReservationResult {
    }

    record Expired(String eventId, String reservationProcessId, String holdId) implements ReservationResult {
    }

    // reason describe la inconsistencia que detectó la cátedra; solo sirve para el log
    record Invalid(String eventId, String reservationProcessId, String holdId,
                   String reason) implements ReservationResult {
    }

    // reason puede faltar
    record Cancelled(String eventId, String reservationProcessId, String holdId, Instant occurredAt,
                     String reason) implements ReservationResult {
    }
}
