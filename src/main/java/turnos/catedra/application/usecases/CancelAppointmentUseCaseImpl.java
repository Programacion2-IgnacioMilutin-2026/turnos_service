package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.application.support.CatedraOperationRunner;
import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.ports.in.CancelAppointmentUseCase;
import turnos.catedra.domain.ports.out.CatedraAppointmentClient;

@Component
@RequiredArgsConstructor
public class CancelAppointmentUseCaseImpl implements CancelAppointmentUseCase {

    private final CatedraAppointmentClient catedraAppointmentClient;
    private final CatedraOperationRunner catedraOperationRunner;

    // Repetir una cancelación es seguro: si un intento anterior se procesó, la cátedra responde lo mismo. Por eso el
    // cliente informa toda falla como no disponible y el componente común la reintenta
    @Override
    public AppointmentCancellationResult cancelAppointment(String reservationProcessId, String reason) {
        CatedraResponse<AppointmentCancellationResult> response = catedraOperationRunner.run("cancellation of " + reservationProcessId,
                token -> catedraAppointmentClient.cancelAppointment(reservationProcessId, reason, token));
        return switch (response) {
            case CatedraResponse.Answered<AppointmentCancellationResult> answered -> answered.content();
            case CatedraResponse.ConfigurationError<AppointmentCancellationResult> error ->
                    new AppointmentCancellationResult.ConfigurationError(error.reason());
            case CatedraResponse.Unavailable<AppointmentCancellationResult> unavailable ->
                    new AppointmentCancellationResult.Unavailable(unavailable.reason());
            // El cliente de la cancelación no informa respuestas inciertas; si llegara una, la cátedra no respondió
            case CatedraResponse.Uncertain<AppointmentCancellationResult> uncertain ->
                    new AppointmentCancellationResult.Unavailable(uncertain.reason());
            // El componente común renueva la sesión ante un rechazo; si igual llegara uno, la cátedra no está disponible
            case CatedraResponse.TokenRejected<AppointmentCancellationResult> _ ->
                    new AppointmentCancellationResult.Unavailable("Technical token rejected");
            case CatedraResponse.Failed<AppointmentCancellationResult> failed ->
                    new AppointmentCancellationResult.Failed(failed.reason());
        };
    }
}
