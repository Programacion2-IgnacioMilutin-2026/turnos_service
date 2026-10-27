package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catedra.application.support.CatedraOperationRunner;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.HoldResult;
import turnos.catedra.domain.ports.in.CreateHoldUseCase;
import turnos.catedra.domain.ports.out.CatedraHoldClient;

import java.time.LocalDate;
import java.time.LocalTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateHoldUseCaseImpl implements CreateHoldUseCase {

    private final CatedraHoldClient catedraHoldClient;
    private final CatedraOperationRunner catedraOperationRunner;

    // Si el pedido pudo haber creado el hold sin que llegara la respuesta, no se repite: repetirlo chocaría con ese
    // mismo hold. Se avisa en el log y se informa que la cátedra no está disponible; si el hold existe, vence solo
    @Override
    public HoldResult createHold(Long professionalId, LocalDate date, LocalTime startTime, String externalPatientId) {
        String operation = "hold request for professional " + professionalId + " on " + date + " at " + startTime;
        CatedraResponse<HoldResult> response = catedraOperationRunner.run(operation,
                token -> catedraHoldClient.createHold(professionalId, date, startTime, externalPatientId, token));
        return switch (response) {
            case CatedraResponse.Answered<HoldResult> answered -> answered.content();
            case CatedraResponse.ConfigurationError<HoldResult> error -> new HoldResult.ConfigurationError(error.reason());
            case CatedraResponse.Unavailable<HoldResult> unavailable -> new HoldResult.Unavailable(unavailable.reason());
            case CatedraResponse.Uncertain<HoldResult> uncertain -> {
                log.warn("Catedra {} may have created a hold that will expire on its own: {}", operation, uncertain.reason());
                yield new HoldResult.Unavailable(uncertain.reason());
            }
            // El componente común renueva la sesión ante un rechazo; si igual llegara uno, la cátedra no está disponible
            case CatedraResponse.TokenRejected<HoldResult> _ -> new HoldResult.Unavailable("Technical token rejected");
            case CatedraResponse.Failed<HoldResult> failed -> new HoldResult.Failed(failed.reason());
        };
    }
}
