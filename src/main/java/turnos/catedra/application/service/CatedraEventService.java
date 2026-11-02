package turnos.catedra.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AppointmentResult;
import turnos.catedra.domain.ports.in.ReceiveAdditionalInformationRequestUseCase;
import turnos.catedra.domain.ports.in.ReceiveAppointmentResultUseCase;

@Service
@RequiredArgsConstructor
public class CatedraEventService {

    private final ReceiveAdditionalInformationRequestUseCase receiveAdditionalInformationRequestUseCase;
    private final ReceiveAppointmentResultUseCase receiveAppointmentResultUseCase;

    public void receiveAdditionalInformationRequest(AdditionalInformationRequest request) {
        receiveAdditionalInformationRequestUseCase.receiveAdditionalInformationRequest(request);
    }

    public void receiveAppointmentResult(AppointmentResult result) {
        receiveAppointmentResultUseCase.receiveAppointmentResult(result);
    }
}
