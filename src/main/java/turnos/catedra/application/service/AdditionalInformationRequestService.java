package turnos.catedra.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.ports.in.ReceiveAdditionalInformationRequestUseCase;

@Service
@RequiredArgsConstructor
public class AdditionalInformationRequestService {

    private final ReceiveAdditionalInformationRequestUseCase receiveAdditionalInformationRequestUseCase;

    public void receiveAdditionalInformationRequest(AdditionalInformationRequest request) {
        receiveAdditionalInformationRequestUseCase.receiveAdditionalInformationRequest(request);
    }
}
