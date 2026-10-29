package turnos.availability.domain.model;

import lombok.*;

import java.time.LocalTime;

// Un turno libre. El profesional y la fecha son los de la consulta
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreeSlot {
    private LocalTime startTime;
    private int durationMinutes;
}
