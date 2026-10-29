package turnos.catedra.domain.ports.in;

import turnos.catedra.domain.model.OccupanciesResult;

import java.time.LocalDate;

public interface GetOccupanciesUseCase {
    OccupanciesResult getOccupancies(Long professionalId, LocalDate date);
}
