package turnos.reservation.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

// Lo que la cátedra tiene registrado sobre un proceso. Copia propia de reservation: el slice no depende del modelo de
// catedra. Tipo inmutable: excepción a la skill autorizada por el usuario.
// confirmedAt llega en una reserva confirmada y cancelledAt en una cancelada; los demás datos pueden faltar
public record ReservationRecord(String reservationProcessId, ReservationRecordStatus status, Long reservationId,
                                Instant confirmedAt, Instant cancelledAt, String cancellationReason,
                                Long professionalId, LocalDate date, LocalTime startTime) {

    public ReservationRecord {
        Objects.requireNonNull(reservationProcessId, "reservationProcessId");
        Objects.requireNonNull(status, "status");
    }
}
