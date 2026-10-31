package turnos.catedra.infrastructure.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.Instant;

// Un mensaje del topic de acciones de la cátedra: los campos del pedido de información adicional, que es el único
// tipo que se procesa por ahora. De los demás tipos solo importa eventType, para reconocerlos
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppointmentActionMessage {
    private String eventId;
    private String eventType;
    private String reservationProcessId;
    private String holdId;
    private String requiredField;
    private Instant expiresAt;
    private Integer schemaVersion;
}
