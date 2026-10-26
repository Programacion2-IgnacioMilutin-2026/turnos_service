package turnos.availability.domain.ports.out;

import turnos.availability.domain.model.OccupancySnapshot;

import java.time.LocalDate;
import java.util.Optional;

public interface OccupancyCache {
    Optional<OccupancySnapshot> find(Long professionalId, LocalDate date);

    void save(Long professionalId, LocalDate date, OccupancySnapshot snapshot);

    void remove(Long professionalId, LocalDate date);
}
