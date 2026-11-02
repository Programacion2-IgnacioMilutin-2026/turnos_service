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
    // Hasta este largo se guarda un motivo informado por la cátedra
    public static final int MAX_REASON_LENGTH = 500;

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
    // Lo asigna la cátedra al confirmar la reserva
    private Long reservationId;
    private Instant confirmedAt;
    private Instant cancelledAt;
    private String cancellationReason;
    // Por qué la cátedra rechazó el teléfono; solo tiene valor mientras el proceso espera otro
    private String rejectionReason;

    public boolean isOpen() {
        return status != null && status.isOpen();
    }

    // Vencido cuando el instante supera el vencimiento de la cátedra más el margen de seguridad
    public boolean hasExpired(Instant now, Duration margin) {
        return expiresAt != null && now.isAfter(expiresAt.plus(margin));
    }

    // La cátedra aceptó la confirmación inicial del hold: solo un proceso con el turno bloqueado pasa a esperar el
    // teléfono. La reserva todavía no está confirmada: eso lo hace confirmReservation
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
        close(ReservationStatus.EXPIRED);
    }

    // Solo se cierran por reloj los que esperan una acción del usuario; uno con el teléfono enviado puede tener
    // su resultado en camino y no se cierra así. Devuelve si el proceso pasó a EXPIRED
    public boolean expireIfOverdue(Instant now, Duration margin) {
        if ((status == ReservationStatus.HELD || status == ReservationStatus.WAITING_FOR_PHONE) && hasExpired(now, margin)) {
            close(ReservationStatus.EXPIRED);
            return true;
        }
        return false;
    }

    // La cátedra confirmó la reserva. Es la fuente de verdad de lo reservado, así que los datos del turno que informe
    // reemplazan a los guardados; los que no informe quedan como estaban
    public void confirmReservation(Long confirmedReservationId, Instant occurredAt, Long confirmedProfessionalId,
                                   LocalDate confirmedDate, LocalTime confirmedStartTime) {
        requireOpen("confirmed");
        close(ReservationStatus.CONFIRMED);
        reservationId = confirmedReservationId;
        confirmedAt = occurredAt;
        if (confirmedProfessionalId != null) {
            professionalId = confirmedProfessionalId;
        }
        if (confirmedDate != null) {
            date = confirmedDate;
        }
        if (confirmedStartTime != null) {
            startTime = confirmedStartTime;
        }
    }

    // Un rechazo solo tiene sentido si el teléfono ya se envió
    public boolean canBeRejected() {
        return status == ReservationStatus.PHONE_SUBMITTED;
    }

    // La cátedra rechazó el teléfono: el usuario puede enviar otro hasta el vencimiento que informa. El pedido de
    // teléfono guardado sigue siendo válido para responderlo
    public void rejectPhone(String reason, Instant rejectionExpiresAt) {
        if (!canBeRejected()) {
            throw new IllegalStateException("Only a process with the phone submitted can be rejected, this one is " + status);
        }
        status = ReservationStatus.WAITING_FOR_PHONE;
        expiresAt = rejectionExpiresAt;
        rejectionReason = limited(reason);
    }

    // Un evento de la cátedra informa el vencimiento: a diferencia de expire, también cierra un proceso con el teléfono
    // enviado, porque ya no hay ningún resultado en camino
    public void closeAsExpired() {
        requireOpen("closed as expired");
        close(ReservationStatus.EXPIRED);
    }

    // La cátedra informó el proceso como inválido: es el único cierre que queda para reconciliar
    public void invalidate() {
        requireOpen("invalidated");
        close(ReservationStatus.INVALID);
        needsReconciliation = true;
    }

    // Una reserva confirmada todavía se puede cancelar; es la única salida de un estado final
    public boolean canBeCancelled() {
        return isOpen() || status == ReservationStatus.CONFIRMED;
    }

    public void cancel(Instant occurredAt, String reason) {
        if (!canBeCancelled()) {
            throw new IllegalStateException("Only an open or confirmed process can be cancelled, this one is " + status);
        }
        close(ReservationStatus.CANCELLED);
        cancelledAt = occurredAt;
        cancellationReason = limited(reason);
    }

    private void requireOpen(String action) {
        if (!isOpen()) {
            throw new IllegalStateException("Only an open process can be " + action + ", this one is " + status);
        }
    }

    // Un proceso cerrado no tiene nada que reconciliar ni un teléfono que corregir
    private void close(ReservationStatus finalStatus) {
        status = finalStatus;
        rejectionReason = null;
        needsReconciliation = false;
    }

    // Recorta sin partir un carácter de dos unidades, que la base rechazaría
    private static String limited(String reason) {
        if (reason == null || reason.length() <= MAX_REASON_LENGTH) {
            return reason;
        }
        int end = Character.isHighSurrogate(reason.charAt(MAX_REASON_LENGTH - 1)) ? MAX_REASON_LENGTH - 1 : MAX_REASON_LENGTH;
        return reason.substring(0, end);
    }
}
