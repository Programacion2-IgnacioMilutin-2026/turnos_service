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
    // El estado local puede no coincidir con el de la cátedra; lo encienden las operaciones y lo limpia la reconciliación
    private boolean needsReconciliation;
    // Identifica el pedido de teléfono de la cátedra: hace falta para responderlo
    private String requestEventId;

    public boolean isOpen() {
        return status != null && status.isOpen();
    }

    // Vencido cuando el instante supera el vencimiento de la cátedra más el margen de seguridad
    public boolean hasExpired(Instant now, Duration margin) {
        return expiresAt != null && now.isAfter(expiresAt.plus(margin));
    }

    // La cátedra aceptó la confirmación: solo un proceso con el turno bloqueado pasa a esperar el teléfono
    public void confirm(Instant confirmedExpiresAt) {
        if (status != ReservationStatus.HELD) {
            throw new IllegalStateException("Only a held process can be confirmed, this one is " + status);
        }
        status = ReservationStatus.WAITING_FOR_PHONE;
        expiresAt = confirmedExpiresAt;
    }

    // Solo un proceso que todavía espera algo de la cátedra o del usuario recibe el pedido de teléfono; uno que ya
    // avanzó o terminó no vuelve atrás
    public boolean canAcceptPhoneRequest() {
        return status == ReservationStatus.HELD || status == ReservationStatus.WAITING_FOR_PHONE;
    }

    // El pedido de teléfono prueba que la cátedra confirmó el hold, aunque el proceso todavía figure con el turno
    // bloqueado. Se guarda para poder responderlo, con el vencimiento que informa la cátedra
    public void acceptPhoneRequest(String phoneRequestEventId, Instant requestExpiresAt) {
        if (!canAcceptPhoneRequest()) {
            throw new IllegalStateException("Only a held process or one waiting for the phone accepts a phone request, this one is " + status);
        }
        status = ReservationStatus.WAITING_FOR_PHONE;
        requestEventId = phoneRequestEventId;
        expiresAt = requestExpiresAt;
    }

    public void markForReconciliation() {
        needsReconciliation = true;
    }

    // La cátedra dijo que el hold venció: se cierra aunque el reloj local todavía no lo indique
    public void expire() {
        if (status != ReservationStatus.HELD && status != ReservationStatus.WAITING_FOR_PHONE) {
            throw new IllegalStateException("Only a process waiting for the user can expire, this one is " + status);
        }
        status = ReservationStatus.EXPIRED;
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
