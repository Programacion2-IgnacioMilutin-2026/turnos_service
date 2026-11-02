package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.application.exception.CatedraEventNotSavedException;
import turnos.catedra.domain.model.AppointmentResult;
import turnos.catedra.domain.model.CatedraEventResult;
import turnos.catedra.domain.ports.in.ReceiveAppointmentResultUseCase;
import turnos.catedra.domain.ports.out.CatedraEventHandler;

@Component
@RequiredArgsConstructor
public class ReceiveAppointmentResultUseCaseImpl implements ReceiveAppointmentResultUseCase {

    private final CatedraEventHandler catedraEventHandler;

    // Termina sin error si el resultado quedó resuelto, procesado o descartado. Si no se pudo guardar, lanza
    // CatedraEventNotSavedException para que quien lo recibió lo vuelva a intentar
    @Override
    public void receiveAppointmentResult(AppointmentResult result) {
        CatedraEventResult delivery = catedraEventHandler.handleAppointmentResult(result);
        if (delivery instanceof CatedraEventResult.NotSaved notSaved) {
            throw new CatedraEventNotSavedException(result.eventId(), notSaved.reason());
        }
    }
}
