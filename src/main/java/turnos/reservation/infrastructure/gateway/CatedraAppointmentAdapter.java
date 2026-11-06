package turnos.reservation.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.Appointment;
import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.catedra.domain.model.AppointmentLookupResult;
import turnos.catedra.domain.ports.in.CancelAppointmentUseCase;
import turnos.catedra.domain.ports.in.FindAppointmentUseCase;
import turnos.reservation.application.exception.ReservationAppointmentNotFoundException;
import turnos.reservation.application.exception.ReservationAppointmentOwnerMismatchException;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationNotCancellableException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.domain.model.ReservationRecord;
import turnos.reservation.domain.model.ReservationRecordStatus;
import turnos.reservation.domain.ports.out.AppointmentCanceller;
import turnos.reservation.domain.ports.out.AppointmentFinder;

import java.time.Instant;
import java.util.Optional;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Entra por el puerto de entrada de catedra y traduce lo que devuelve
@Slf4j
@Component
@RequiredArgsConstructor
public class CatedraAppointmentAdapter implements AppointmentCanceller, AppointmentFinder {

    private final CancelAppointmentUseCase cancelAppointmentUseCase;
    private final FindAppointmentUseCase findAppointmentUseCase;

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

    // El registro llega validado por catedra; aquí solo se copia al modelo de reservation. El log de cada caso lo escribe
    // quien reconcilia, que sabe qué hace con el proceso
    @Override
    public Optional<ReservationRecord> find(String externalPatientId, String reservationProcessId) {
        return switch (findAppointmentUseCase.findAppointment(externalPatientId, reservationProcessId)) {
            case AppointmentLookupResult.Found found -> Optional.of(toRecord(found.appointment()));
            case AppointmentLookupResult.NotFound _ -> Optional.empty();
            case AppointmentLookupResult.ConfigurationError error -> throw new ReservationServiceDegradedException(error.reason());
            case AppointmentLookupResult.Unavailable unavailable -> throw new ReservationCentralUnavailableException(unavailable.reason());
            case AppointmentLookupResult.Failed failed -> throw new ReservationIntegrationException(failed.reason());
        };
    }

    private static ReservationRecord toRecord(Appointment appointment) {
        return new ReservationRecord(appointment.reservationProcessId(), status(appointment), appointment.reservationId(),
                appointment.confirmedAt(), appointment.cancelledAt(), appointment.cancellationReason(),
                appointment.professionalId(), appointment.date(), appointment.startTime());
    }

    private static ReservationRecordStatus status(Appointment appointment) {
        return switch (appointment.status()) {
            case PHONE_PENDING -> ReservationRecordStatus.PHONE_PENDING;
            case CONFIRMED -> ReservationRecordStatus.CONFIRMED;
            case CANCELLED -> ReservationRecordStatus.CANCELLED;
            case FAILED -> ReservationRecordStatus.FAILED;
            // catedra no entrega un registro con estado desconocido; si llegara, no se puede usar
            case UNKNOWN -> throw new ReservationIntegrationException("Catedra answered an appointment with an unknown status");
        };
    }
}
