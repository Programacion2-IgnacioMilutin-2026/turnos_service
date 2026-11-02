package turnos.catedra.domain.ports.out;

import turnos.catedra.domain.model.AdditionalInformationSubmission;
import turnos.catedra.domain.model.AdditionalInformationSubmissionResult;
import turnos.catedra.domain.model.TechnicalIntegration;

// Envía la respuesta a la cátedra, con los datos de la integración vigente. Reintenta lo que se puede reintentar;
// si no lo logra lo informa en el resultado, sin lanzar excepciones
public interface AdditionalInformationPublisher {

    AdditionalInformationSubmissionResult publish(AdditionalInformationSubmission submission, TechnicalIntegration integration);

}
