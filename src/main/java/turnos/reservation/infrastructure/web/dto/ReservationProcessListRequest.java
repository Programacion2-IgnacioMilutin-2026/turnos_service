package turnos.reservation.infrastructure.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Los parámetros de la URL, todos opcionales; uno presente pero vacío cuenta como si no viniera. Un valor que no se puede
// convertir también se informa como error del campo. El estado es texto porque no todos se pueden pedir: un enum
// aceptaría también los que no se listan. Los mensajes son propios: los de Bean Validation cambian con el idioma
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ReservationProcessListRequest {
    @Pattern(regexp = "|HELD|WAITING_FOR_PHONE|PHONE_SUBMITTED|CONFIRMED|CANCELLED",
            message = "Status must be HELD, WAITING_FOR_PHONE, PHONE_SUBMITTED, CONFIRMED or CANCELLED")
    private String status;

    @Min(value = 0, message = "Page must not be negative")
    private Integer page;

    @Min(value = 1, message = "Size must be between 1 and 100")
    @Max(value = 100, message = "Size must be between 1 and 100")
    private Integer size;
}
