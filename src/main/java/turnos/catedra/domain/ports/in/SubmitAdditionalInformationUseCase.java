package turnos.catedra.domain.ports.in;

import turnos.catedra.domain.model.AdditionalInformationSubmissionResult;

public interface SubmitAdditionalInformationUseCase {
    AdditionalInformationSubmissionResult submitAdditionalInformation(String reservationProcessId, String holdId,
                                                                      String requestEventId, String phoneNumber);
}
