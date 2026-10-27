package turnos.reservation.domain.model;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Lo que el bloqueo necesita del profesional: si atiende, sus datos para el historial y sus horarios
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationProfessional {
    private boolean enabled;
    private String firstName;
    private String lastName;
    private String categoryName;
    private List<ReservationSchedule> schedules;

    // Alcanza con que un horario habilitado de ese día genere el turno
    public boolean offersSlot(LocalDate date, LocalTime startTime) {
        return schedules.stream().anyMatch(schedule -> schedule.generates(date.getDayOfWeek(), startTime));
    }
}
