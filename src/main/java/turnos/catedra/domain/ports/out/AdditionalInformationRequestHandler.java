package turnos.catedra.domain.ports.out;

import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AdditionalInformationRequestResult;

// Quien procesa los pedidos de información adicional que llegan de la cátedra
public interface AdditionalInformationRequestHandler {

    AdditionalInformationRequestResult handleAdditionalInformationRequest(AdditionalInformationRequest request);

}
