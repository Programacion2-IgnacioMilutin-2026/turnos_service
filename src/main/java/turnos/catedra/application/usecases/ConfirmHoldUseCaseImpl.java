package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.application.support.CatedraOperationRunner;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.HoldConfirmationResult;
import turnos.catedra.domain.ports.in.ConfirmHoldUseCase;
import turnos.catedra.domain.ports.out.CatedraHoldClient;

@Component
@RequiredArgsConstructor
public class ConfirmHoldUseCaseImpl implements ConfirmHoldUseCase {

    private final CatedraHoldClient catedraHoldClient;
    private final CatedraOperationRunner catedraOperationRunner;

    // Repetir una confirmación es seguro: si un intento anterior se procesó, la cátedra responde que ya estaba
    // confirmado. Por eso el cliente informa toda falla como no disponible y el componente común la reintenta
    @Override
    public HoldConfirmationResult confirmHold(String holdId, String externalPatientId, String patientFirstName,
                                              String patientLastName) {
        CatedraResponse<HoldConfirmationResult> response = catedraOperationRunner.run("confirmation of " + holdId,
                token -> catedraHoldClient.confirmHold(holdId, externalPatientId, patientFirstName, patientLastName, token));
        return switch (response) {
            case CatedraResponse.Answered<HoldConfirmationResult> answered -> answered.content();
            case CatedraResponse.ConfigurationError<HoldConfirmationResult> error ->
                    new HoldConfirmationResult.ConfigurationError(error.reason());
            case CatedraResponse.Unavailable<HoldConfirmationResult> unavailable ->
                    new HoldConfirmationResult.Unavailable(unavailable.reason());
            // El cliente de la confirmación no informa respuestas inciertas; si llegara una, la cátedra no respondió
            case CatedraResponse.Uncertain<HoldConfirmationResult> uncertain ->
                    new HoldConfirmationResult.Unavailable(uncertain.reason());
            // El componente común renueva la sesión ante un rechazo; si igual llegara uno, la cátedra no está disponible
            case CatedraResponse.TokenRejected<HoldConfirmationResult> _ ->
                    new HoldConfirmationResult.Unavailable("Technical token rejected");
            case CatedraResponse.Failed<HoldConfirmationResult> failed -> new HoldConfirmationResult.Failed(failed.reason());
        };
    }
}
