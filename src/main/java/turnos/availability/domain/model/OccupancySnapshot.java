package turnos.availability.domain.model;

import lombok.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

// Las horas ocupadas de un profesional en una fecha, y cuándo se obtuvieron de la cátedra
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OccupancySnapshot {
    private List<LocalTime> occupiedStartTimes;
    private Instant dataAsOf;

    public boolean isOlderThan(Duration maxAge, Instant now) {
        return dataAsOf.plus(maxAge).isBefore(now);
    }
}
