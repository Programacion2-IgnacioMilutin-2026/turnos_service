package turnos.catedra.domain.ports.out;

import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.CatedraEventResult;

// Quien procesa los eventos que llegan de la cátedra
public interface CatedraEventHandler {

    CatedraEventResult handleAdditionalInformationRequest(AdditionalInformationRequest request);

}
