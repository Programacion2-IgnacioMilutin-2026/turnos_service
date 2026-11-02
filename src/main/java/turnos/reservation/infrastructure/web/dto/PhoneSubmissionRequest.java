package turnos.reservation.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

// Solo se exige que venga: el formato lo valida el caso de uso, que responde con su propio código. Es un dato personal:
// no tiene toString para que no termine en un log
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhoneSubmissionRequest {

    @NotNull(message = "Phone number is mandatory")
    private String phoneNumber;
}
