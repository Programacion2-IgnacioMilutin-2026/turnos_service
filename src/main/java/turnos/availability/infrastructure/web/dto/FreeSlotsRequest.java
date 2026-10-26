package turnos.availability.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

// Los parámetros de la URL. Un valor que no se puede convertir (texto, número fuera de rango, fecha imposible)
// también se informa como error de validación del campo
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FreeSlotsRequest {
    @NotNull
    @Positive
    private Long professionalId;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;
}
