package turnos.availability.domain.ports.out;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Las horas de inicio ya tomadas. Si la cátedra no responde o la integración falla, lanza la excepción de availability
public interface OccupancyProvider {
    List<LocalTime> getOccupiedStartTimes(Long professionalId, LocalDate date);
}
