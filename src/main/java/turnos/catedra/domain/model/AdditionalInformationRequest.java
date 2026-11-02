package turnos.catedra.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// La cátedra pide el teléfono del paciente de un proceso que confirmó. Ya validado: tiene todo lo que hace falta para
// procesarlo. eventId identifica el pedido y es lo que se usa para responderlo
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdditionalInformationRequest {
    private String eventId;
    private String reservationProcessId;
    private String holdId;
    private Instant expiresAt;
}
