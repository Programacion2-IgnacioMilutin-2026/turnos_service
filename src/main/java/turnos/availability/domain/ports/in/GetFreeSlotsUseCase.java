package turnos.availability.domain.ports.in;

import turnos.availability.domain.model.FreeSlotsResult;

import java.time.LocalDate;

public interface GetFreeSlotsUseCase {
    FreeSlotsResult getFreeSlots(Long professionalId, LocalDate date, String userToken);
}
