package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.application.exception.CatedraEventNotSavedException;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.CatedraEventResult;
import turnos.catedra.domain.ports.in.ReceiveAdditionalInformationRequestUseCase;
import turnos.catedra.domain.ports.out.CatedraEventHandler;

@Component
@RequiredArgsConstructor
public class ReceiveAdditionalInformationRequestUseCaseImpl implements ReceiveAdditionalInformationRequestUseCase {

    private final CatedraEventHandler catedraEventHandler;

    // Termina sin error si el pedido quedó resuelto, procesado o descartado. Si no se pudo guardar, lanza
    // CatedraEventNotSavedException para que quien lo recibió lo vuelva a intentar
    @Override
    public void receiveAdditionalInformationRequest(AdditionalInformationRequest request) {
        CatedraEventResult result = catedraEventHandler.handleAdditionalInformationRequest(request);
        if (result instanceof CatedraEventResult.NotSaved notSaved) {
            throw new CatedraEventNotSavedException(request.getEventId(), notSaved.reason());
        }
    }
}
