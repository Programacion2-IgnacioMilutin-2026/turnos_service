package turnos.catedra.domain.ports.in;

import turnos.catedra.domain.model.HoldResult;

import java.time.LocalDate;
import java.time.LocalTime;

public interface CreateHoldUseCase {
    HoldResult createHold(Long professionalId, LocalDate date, LocalTime startTime, String externalPatientId);
}
