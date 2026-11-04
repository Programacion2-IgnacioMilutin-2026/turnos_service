package turnos.reservation.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.catedra.domain.ports.in.CancelAppointmentUseCase;
import turnos.reservation.application.exception.ReservationAppointmentNotFoundException;
import turnos.reservation.application.exception.ReservationAppointmentOwnerMismatchException;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationNotCancellableException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.domain.ports.out.AppointmentCanceller;

import java.time.Instant;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Entra por el puerto de entrada de catedra y traduce lo que devuelve
@Slf4j
@Component
@RequiredArgsConstructor
public class CatedraAppointmentAdapter implements AppointmentCanceller {

    private final CancelAppointmentUseCase cancelAppointmentUseCase;

    @Override
    public Instant cancel(String reservationProcessId, String reason) {
        return switch (cancelAppointmentUseCase.cancelAppointment(reservationProcessId, reason)) {
            case AppointmentCancellationResult.Cancelled cancelled -> cancelled.cancelledAt();
            case AppointmentCancellationResult.NotFound _ -> {
                log.error("Catedra does not know reservation process {}, which is confirmed here", reservationProcessId);
                throw new ReservationAppointmentNotFoundException(reservationProcessId);
            }
            case AppointmentCancellationResult.OwnerMismatch _ -> {
                log.error("Catedra says reservation process {} belongs to another technical account", reservationProcessId);
                throw new ReservationAppointmentOwnerMismatchException(reservationProcessId);
            }
            case AppointmentCancellationResult.NotCancellable _ -> {
                log.warn("Catedra does not allow cancelling reservation process {}, which is confirmed here", reservationProcessId);
                throw new ReservationNotCancellableException(reservationProcessId);
            }
            case AppointmentCancellationResult.ConfigurationError error -> throw new ReservationServiceDegradedException(error.reason());
            case AppointmentCancellationResult.Unavailable unavailable -> throw new ReservationCentralUnavailableException(unavailable.reason());
            case AppointmentCancellationResult.Failed failed -> {
                log.error("Catedra cancellation of reservation process {} failed: {}", reservationProcessId, failed.reason());
                throw new ReservationIntegrationException(failed.reason());
            }
        };
    }
}
