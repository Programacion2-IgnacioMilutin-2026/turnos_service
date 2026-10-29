package turnos.reservation.domain.model;

import lombok.*;

import java.time.Instant;

// La cátedra aceptó la confirmación, o ya la había aceptado antes. En ese caso no informa el vencimiento
// y expiresAt queda vacío
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationHoldConfirmation {
    private boolean alreadyConfirmed;
    private Instant expiresAt;
}
