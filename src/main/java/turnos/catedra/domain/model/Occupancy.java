package turnos.catedra.domain.model;

import lombok.*;

import java.time.LocalTime;

// Un turno tomado en la cátedra. Solo lo que se usa: confirmado o retenido, ocupa igual
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Occupancy {
    private LocalTime startTime;
    private OccupancyStatus status;
}
