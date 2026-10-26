package turnos.catedra.domain.ports.out;

import turnos.catedra.domain.model.OccupanciesResult;

import java.time.LocalDate;

public interface CatedraOccupancyClient {
    OccupanciesResult fetchOccupancies(Long professionalId, LocalDate date, String technicalToken);
}
