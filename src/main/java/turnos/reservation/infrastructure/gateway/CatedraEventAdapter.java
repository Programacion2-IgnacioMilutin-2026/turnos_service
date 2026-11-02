package turnos.reservation.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AppointmentResult;
import turnos.catedra.domain.model.CatedraEventResult;
import turnos.catedra.domain.ports.out.CatedraEventHandler;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.service.ReservationService;
import turnos.reservation.domain.model.ReservationEventOutcome;
import turnos.reservation.domain.model.ReservationPhoneRequest;
import turnos.reservation.domain.model.ReservationResult;

import java.util.function.Supplier;

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
        return deliver(() -> reservationService.processPhoneRequest(phoneRequest));
    }

    @Override
    public CatedraEventResult handleAppointmentResult(AppointmentResult result) {
        ReservationResult reservationResult = toReservationResult(result);
        return deliver(() -> reservationService.processReservationResult(reservationResult));
    }

    private static CatedraEventResult deliver(Supplier<ReservationEventOutcome> processing) {
        try {
            ReservationEventOutcome outcome = processing.get();
            return outcome == ReservationEventOutcome.APPLIED
                    ? new CatedraEventResult.Processed()
                    : new CatedraEventResult.Discarded(outcome.name());
        } catch (ReservationNotSavedException e) {
            return new CatedraEventResult.NotSaved(e.getMessage());
        }
    }

    private static ReservationResult toReservationResult(AppointmentResult result) {
        return switch (result) {
            case AppointmentResult.Confirmed c -> new ReservationResult.Confirmed(c.eventId(), c.reservationProcessId(),
                    c.holdId(), c.reservationId(), c.occurredAt(), c.professionalId(), c.date(), c.startTime());
            case AppointmentResult.Rejected r -> new ReservationResult.Rejected(r.eventId(), r.reservationProcessId(),
                    r.holdId(), r.reason(), r.expiresAt());
            case AppointmentResult.Expired e -> new ReservationResult.Expired(e.eventId(), e.reservationProcessId(),
                    e.holdId());
            case AppointmentResult.Invalid i -> new ReservationResult.Invalid(i.eventId(), i.reservationProcessId(),
                    i.holdId(), i.reason());
            case AppointmentResult.Cancelled c -> new ReservationResult.Cancelled(c.eventId(), c.reservationProcessId(),
                    c.holdId(), c.occurredAt(), c.reason());
        };
    }
}
