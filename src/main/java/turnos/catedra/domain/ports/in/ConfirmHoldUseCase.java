package turnos.catedra.domain.ports.in;

import turnos.catedra.domain.model.HoldConfirmationResult;

public interface ConfirmHoldUseCase {
    HoldConfirmationResult confirmHold(String holdId, String externalPatientId, String patientFirstName,
                                       String patientLastName);
}
