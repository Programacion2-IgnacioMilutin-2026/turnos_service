package turnos.catedra.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// La cátedra aceptó la confirmación: el proceso espera el teléfono hasta expiresAt, que es el mismo del hold
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldConfirmation {
    private String reservationProcessId;
    private Instant expiresAt;
}
