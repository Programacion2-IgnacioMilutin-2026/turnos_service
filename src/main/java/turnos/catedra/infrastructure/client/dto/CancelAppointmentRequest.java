package turnos.catedra.infrastructure.client.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelAppointmentRequest {
    private String reason;
}
