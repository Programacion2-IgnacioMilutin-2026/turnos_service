package turnos.reservation.domain.model;

import lombok.*;

import java.time.Instant;

// El bloqueo que concedió la cátedra: sus ids y hasta cuándo retiene el turno
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationHold {
    private String holdId;
    private String reservationProcessId;
    private Instant expiresAt;
}
