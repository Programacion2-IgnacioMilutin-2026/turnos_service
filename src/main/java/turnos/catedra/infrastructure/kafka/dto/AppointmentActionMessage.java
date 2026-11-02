package turnos.catedra.infrastructure.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

// Un mensaje del topic de acciones de la cátedra: los campos de todos sus tipos. Los instantes, fechas, horas y números
// llegan como texto y los convierte quien lee el mensaje, solo si ese tipo los usa: si Jackson los convirtiera acá, un
// valor mal escrito en un campo que no se usa descartaría el mensaje entero, sin saber cuál era
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
    private String reason;
    private String reservationId;
    private String professionalId;
    private String date;
    private String startTime;
    private String expiresAt;
    private String occurredAt;
    private Integer schemaVersion;
}
