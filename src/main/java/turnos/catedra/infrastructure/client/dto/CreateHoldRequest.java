package turnos.catedra.infrastructure.client.dto;

import lombok.*;

import java.time.LocalDate;

// La hora va como texto HH:mm, el formato que espera la cátedra
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateHoldRequest {
    private Long professionalId;
    private LocalDate date;
    private String startTime;
    private String externalPatientId;
}
