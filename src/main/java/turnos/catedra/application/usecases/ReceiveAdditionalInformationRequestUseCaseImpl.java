package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.application.exception.CatedraEventNotSavedException;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AdditionalInformationRequestResult;
import turnos.catedra.domain.ports.in.ReceiveAdditionalInformationRequestUseCase;
import turnos.catedra.domain.ports.out.AdditionalInformationRequestHandler;

@Component
@RequiredArgsConstructor
public class ReceiveAdditionalInformationRequestUseCaseImpl implements ReceiveAdditionalInformationRequestUseCase {

    private final AdditionalInformationRequestHandler additionalInformationRequestHandler;

    // Termina sin error si el pedido quedó resuelto, procesado o descartado. Si no se pudo guardar, lanza
    // CatedraEventNotSavedException para que quien lo recibió lo vuelva a intentar
    @Override
    public void receiveAdditionalInformationRequest(AdditionalInformationRequest request) {
        AdditionalInformationRequestResult result = additionalInformationRequestHandler.handleAdditionalInformationRequest(request);
        if (result instanceof AdditionalInformationRequestResult.NotSaved notSaved) {
            throw new CatedraEventNotSavedException(request.getEventId(), notSaved.reason());
        }
    }
}
