package turnos.reservation.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AdditionalInformationRequestResult;
import turnos.catedra.domain.ports.out.AdditionalInformationRequestHandler;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.service.ReservationService;
import turnos.reservation.domain.model.PhoneRequestOutcome;
import turnos.reservation.domain.model.ReservationPhoneRequest;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Al revés que los otros adaptadores de gateway, implementa un puerto de salida de catedra y llama a reservation:
// así catedra entrega los pedidos de teléfono sin conocer a reservation. Solo usa el puerto y el modelo de catedra,
// y entra a reservation por su service, como cualquier pedido que llega de afuera
@Component
@RequiredArgsConstructor
public class CatedraPhoneRequestAdapter implements AdditionalInformationRequestHandler {

    private final ReservationService reservationService;

    @Override
    public AdditionalInformationRequestResult handleAdditionalInformationRequest(AdditionalInformationRequest request) {
        ReservationPhoneRequest phoneRequest = ReservationPhoneRequest.builder()
                .eventId(request.getEventId())
                .reservationProcessId(request.getReservationProcessId())
                .holdId(request.getHoldId())
                .expiresAt(request.getExpiresAt())
                .build();
        try {
            PhoneRequestOutcome outcome = reservationService.processPhoneRequest(phoneRequest);
            return outcome == PhoneRequestOutcome.APPLIED
                    ? new AdditionalInformationRequestResult.Processed()
                    : new AdditionalInformationRequestResult.Discarded(outcome.name());
        } catch (ReservationNotSavedException e) {
            return new AdditionalInformationRequestResult.NotSaved(e.getMessage());
        }
    }
}
