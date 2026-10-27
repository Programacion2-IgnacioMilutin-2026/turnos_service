package turnos.reservation.infrastructure.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

// El id se recibe como número decimal: como Long, Jackson convertiría 1.5 en 1 sin avisar. Así 1.5 es un error de
// validación. Los mensajes son propios: los de Bean Validation cambian según el idioma del servidor o del cliente.
// El constructor de todos los campos se escribe a mano (no con Lombok) porque Jackson lo usa para leer el JSON
@Getter
@Setter
@Builder
@NoArgsConstructor
public class ReservationProcessRequest {

    @NotNull(message = "Professional id is mandatory")
    @Positive(message = "Professional id must be a positive number")
    @Digits(integer = 20, fraction = 0, message = "Professional id must be a whole number")
    @DecimalMax(value = "9223372036854775807", message = "Professional id is too large")
    private BigDecimal professionalId;

    @NotNull(message = "Date is mandatory")
    private LocalDate date;

    // HH:mm o HH:mm:ss
    @NotNull(message = "Start time is mandatory")
    private LocalTime startTime;

    public ReservationProcessRequest(BigDecimal professionalId, LocalDate date, LocalTime startTime) {
        this.professionalId = withoutTrailingZeros(professionalId);
        this.date = date;
        this.startTime = startTime;
    }

    public void setProfessionalId(BigDecimal professionalId) {
        this.professionalId = withoutTrailingZeros(professionalId);
    }

    // Sin los ceros finales, 1.0 y 15.000 son los enteros 1 y 15
    private static BigDecimal withoutTrailingZeros(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }
}
