package turnos.catedra.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// La respuesta al pedido de teléfono de la cátedra. Los reintentos de un envío usan este mismo contenido. Lleva un dato
// personal: no tiene toString para que no termine en un log
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdditionalInformationSubmission {
    private String eventId;
    private String reservationProcessId;
    private String holdId;
    private String requestEventId;
    private String phoneNumber;
    private Instant occurredAt;
}
