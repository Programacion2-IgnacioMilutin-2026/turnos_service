package turnos.catedra.domain.ports.in;

import turnos.catedra.domain.model.AdditionalInformationRequest;

public interface ReceiveAdditionalInformationRequestUseCase {
    void receiveAdditionalInformationRequest(AdditionalInformationRequest request);
}
