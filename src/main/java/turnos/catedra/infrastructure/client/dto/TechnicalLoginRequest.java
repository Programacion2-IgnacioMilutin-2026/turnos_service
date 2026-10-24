package turnos.catedra.infrastructure.client.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalLoginRequest {
    private String username;
    private String password;
    private boolean rememberMe;
}
