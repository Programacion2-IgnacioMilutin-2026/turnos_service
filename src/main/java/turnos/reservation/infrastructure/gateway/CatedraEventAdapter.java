package turnos.reservation.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.CatedraEventResult;
import turnos.catedra.domain.ports.out.CatedraEventHandler;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.service.ReservationService;
import turnos.reservation.domain.model.ReservationEventOutcome;
import turnos.reservation.domain.model.ReservationPhoneRequest;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Al revés que los otros adaptadores de gateway, implementa un puerto de salida de catedra y llama a reservation:
// así catedra entrega sus eventos sin conocer a reservation. Solo usa el puerto y el modelo de catedra,
// y entra a reservation por su service, como cualquier pedido que llega de afuera
@Component
@RequiredArgsConstructor
public class CatedraEventAdapter implements CatedraEventHandler {

    private final ReservationService reservationService;

    @Override
    public CatedraEventResult handleAdditionalInformationRequest(AdditionalInformationRequest request) {
        ReservationPhoneRequest phoneRequest = ReservationPhoneRequest.builder()
                .eventId(request.getEventId())
                .reservationProcessId(request.getReservationProcessId())
                .holdId(request.getHoldId())
                .expiresAt(request.getExpiresAt())
                .build();
        try {
            ReservationEventOutcome outcome = reservationService.processPhoneRequest(phoneRequest);
            return outcome == ReservationEventOutcome.APPLIED
                    ? new CatedraEventResult.Processed()
                    : new CatedraEventResult.Discarded(outcome.name());
        } catch (ReservationNotSavedException e) {
            return new CatedraEventResult.NotSaved(e.getMessage());
        }
    }
}
