package turnos.catedra.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HoldResponse {
    private String holdId;
    private String reservationProcessId;
    private String status;
    private Instant expiresAt;
}
