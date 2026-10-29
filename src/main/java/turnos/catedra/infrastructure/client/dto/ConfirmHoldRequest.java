package turnos.catedra.infrastructure.client.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmHoldRequest {
    private String externalPatientId;
    private String patientFirstName;
    private String patientLastName;
}
