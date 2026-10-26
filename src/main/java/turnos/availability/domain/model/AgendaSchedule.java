package turnos.availability.domain.model;

import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalTime;

// Un horario semanal del profesional, tal como lo necesita el cálculo de turnos
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgendaSchedule {
    private DayOfWeek dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private int slotDurationMinutes;
    private boolean enabled;

    public boolean appliesTo(DayOfWeek day) {
        return enabled && dayOfWeek == day;
    }
}
