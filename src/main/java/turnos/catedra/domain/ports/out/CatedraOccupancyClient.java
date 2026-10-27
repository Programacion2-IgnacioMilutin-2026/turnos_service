package turnos.catedra.domain.ports.out;

import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.Occupancy;

import java.time.LocalDate;
import java.util.List;

public interface CatedraOccupancyClient {
    CatedraResponse<List<Occupancy>> fetchOccupancies(Long professionalId, LocalDate date, String technicalToken);
}
