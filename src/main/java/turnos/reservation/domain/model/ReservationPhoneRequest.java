package turnos.reservation.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// El pedido de teléfono que hizo la cátedra para un proceso. Copia propia de reservation: el slice no depende del
// modelo de catedra. eventId es el identificador del pedido, el que se guarda como requestEventId
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationPhoneRequest {
    private String eventId;
    private String reservationProcessId;
    private String holdId;
    private Instant expiresAt;
}
