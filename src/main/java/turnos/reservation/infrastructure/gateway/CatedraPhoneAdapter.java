package turnos.reservation.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.AdditionalInformationSubmissionResult;
import turnos.catedra.domain.ports.in.CheckTechnicalIntegrationUseCase;
import turnos.catedra.domain.ports.in.SubmitAdditionalInformationUseCase;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.ports.out.PhoneSubmitter;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Entra a catedra por sus puertos de entrada y traduce lo que devuelven. El motivo de una falla ya queda en el log de catedra
@Component
@RequiredArgsConstructor
public class CatedraPhoneAdapter implements PhoneSubmitter {

    private final CheckTechnicalIntegrationUseCase checkTechnicalIntegrationUseCase;
    private final SubmitAdditionalInformationUseCase submitAdditionalInformationUseCase;

    @Override
    public boolean canSubmit() {
        return checkTechnicalIntegrationUseCase.checkTechnicalIntegration();
    }

    @Override
    public void submit(ReservationProcess reservationProcess) {
        AdditionalInformationSubmissionResult result = submitAdditionalInformationUseCase.submitAdditionalInformation(
                reservationProcess.getReservationProcessId(), reservationProcess.getHoldId(),
                reservationProcess.getRequestEventId(), reservationProcess.getPhoneNumber());
        if (result instanceof AdditionalInformationSubmissionResult.NotPublished notPublished) {
            throw new ReservationCentralUnavailableException(notPublished.reason());
        }
    }
}
