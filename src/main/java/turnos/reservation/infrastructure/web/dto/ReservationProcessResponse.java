package turnos.reservation.infrastructure.web.dto;

import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

// El proceso de reserva tal como lo ve la app; los campos sin valor salen en null. Nunca expone el holdId
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationProcessResponse {
    private String reservationProcessId;
    private String status;
    private Long professionalId;
    private String professionalFirstName;
    private String professionalLastName;
    private String professionalCategoryName;
    private LocalDate date;
    // HH:mm, sin segundos
    private String startTime;
    private Instant expiresAt;
    private String rejectionReason;
    private Long reservationId;
    private Instant confirmedAt;
    private Instant cancelledAt;
    private String cancellationReason;
}
