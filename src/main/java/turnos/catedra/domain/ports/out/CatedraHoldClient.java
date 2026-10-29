package turnos.catedra.domain.ports.out;

import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.HoldConfirmationResult;
import turnos.catedra.domain.model.HoldResult;

import java.time.LocalDate;
import java.time.LocalTime;

public interface CatedraHoldClient {
    CatedraResponse<HoldResult> createHold(Long professionalId, LocalDate date, LocalTime startTime,
                                           String externalPatientId, String technicalToken);

    CatedraResponse<HoldConfirmationResult> confirmHold(String holdId, String externalPatientId, String patientFirstName,
                                                        String patientLastName, String technicalToken);
}
