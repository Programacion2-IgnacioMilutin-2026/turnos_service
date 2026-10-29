package turnos.reservation.domain.model;

import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalTime;

// Un horario semanal del profesional, tal como lo necesita el bloqueo para validar un turno
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationSchedule {
    private DayOfWeek dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private int slotDurationMinutes;
    private boolean enabled;

    // Genera el turno si está habilitado ese día, el turno empieza en el inicio del horario más un múltiplo de la
    // duración y termina en o antes del cierre. Se cuenta en segundos del día: una hora con segundos sueltos no es
    // el inicio de ningún turno, y un turno nunca pasa de la medianoche
    public boolean generates(DayOfWeek day, LocalTime slotStart) {
        if (!enabled || !day.equals(dayOfWeek) || slotDurationMinutes <= 0 || slotStart.getNano() != 0) {
            return false;
        }
        int duration = slotDurationMinutes * 60;
        int offset = slotStart.toSecondOfDay() - startTime.toSecondOfDay();
        return offset >= 0 && offset % duration == 0 && slotStart.toSecondOfDay() + duration <= endTime.toSecondOfDay();
    }
}
