package turnos.reservation.domain.model;

import lombok.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

// Un proceso de reserva de un usuario. Lo identifica el reservationProcessId que asigna la cátedra.
// Los datos del profesional son los del momento del bloqueo: sirven de historial, no como catálogo vigente
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationProcess {
    private String reservationProcessId;
    private String holdId;
    private Long userId;
    private Long professionalId;
    private String professionalFirstName;
    private String professionalLastName;
    private String professionalCategoryName;
    private LocalDate date;
    private LocalTime startTime;
    private ReservationStatus status;
    private Instant expiresAt;

    public boolean isOpen() {
        return status != null && status.isOpen();
    }

    // Vencido cuando el instante supera el vencimiento de la cátedra más el margen de seguridad
    public boolean hasExpired(Instant now, Duration margin) {
        return expiresAt != null && now.isAfter(expiresAt.plus(margin));
    }

    // Solo se cierran por reloj los que esperan una acción del usuario; uno con el teléfono enviado puede tener
    // su resultado en camino y no se cierra así. Devuelve si el proceso pasó a EXPIRED
    public boolean expireIfOverdue(Instant now, Duration margin) {
        if ((status == ReservationStatus.HELD || status == ReservationStatus.WAITING_FOR_PHONE) && hasExpired(now, margin)) {
            status = ReservationStatus.EXPIRED;
            return true;
        }
        return false;
    }
}
