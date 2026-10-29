package turnos.reservation.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.HoldConfirmationResult;
import turnos.catedra.domain.model.HoldResult;
import turnos.catedra.domain.ports.in.ConfirmHoldUseCase;
import turnos.catedra.domain.ports.in.CreateHoldUseCase;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationHoldExpiredException;
import turnos.reservation.application.exception.ReservationHoldNotFoundException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationInvalidSlotException;
import turnos.reservation.application.exception.ReservationPatientMismatchException;
import turnos.reservation.application.exception.ReservationProfessionalDisabledException;
import turnos.reservation.application.exception.ReservationProfessionalNotFoundException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.application.exception.ReservationSlotNotAvailableException;
import turnos.reservation.domain.model.ReservationHold;
import turnos.reservation.domain.model.ReservationHoldConfirmation;
import turnos.reservation.domain.model.ReservationPatient;
import turnos.reservation.domain.ports.out.HoldProvider;

import java.time.LocalDate;
import java.time.LocalTime;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Único lugar de reservation que conoce a catedra: entra por sus puertos de entrada y traduce lo que devuelven
@Slf4j
@Component
@RequiredArgsConstructor
public class CatedraHoldAdapter implements HoldProvider {

    private final CreateHoldUseCase createHoldUseCase;
    private final ConfirmHoldUseCase confirmHoldUseCase;

    @Override
    public ReservationHold requestHold(Long professionalId, LocalDate date, LocalTime startTime, String externalPatientId) {
        return switch (createHoldUseCase.createHold(professionalId, date, startTime, externalPatientId)) {
            case HoldResult.Created created -> ReservationHold.builder()
                    .holdId(created.hold().getHoldId())
                    .reservationProcessId(created.hold().getReservationProcessId())
                    .expiresAt(created.hold().getExpiresAt())
                    .build();
            // Catálogo lo dio por existente hace un instante: la copia de catálogo y la cátedra pueden estar desfasadas
            case HoldResult.ProfessionalNotFound _ -> {
                log.warn("Catedra does not know professional {}, although the catalog does", professionalId);
                throw new ReservationProfessionalNotFoundException(professionalId);
            }
            case HoldResult.ProfessionalDisabled _ -> throw new ReservationProfessionalDisabledException(professionalId);
            case HoldResult.InvalidSlot _ -> throw new ReservationInvalidSlotException(professionalId);
            case HoldResult.SlotNotAvailable _ -> throw new ReservationSlotNotAvailableException(professionalId);
            case HoldResult.ConfigurationError error -> throw new ReservationServiceDegradedException(error.reason());
            case HoldResult.Unavailable unavailable -> throw new ReservationCentralUnavailableException(unavailable.reason());
            case HoldResult.Failed failed -> {
                log.error("Catedra hold request for professional {} failed: {}", professionalId, failed.reason());
                throw new ReservationIntegrationException(failed.reason());
            }
        };
    }

    @Override
    public ReservationHoldConfirmation confirmHold(String holdId, Long professionalId, String externalPatientId,
                                                   ReservationPatient patient) {
        return switch (confirmHoldUseCase.confirmHold(holdId, externalPatientId, patient.getFirstName(), patient.getLastName())) {
            case HoldConfirmationResult.Confirmed confirmed -> ReservationHoldConfirmation.builder()
                    .expiresAt(confirmed.confirmation().getExpiresAt())
                    .build();
            case HoldConfirmationResult.AlreadyConfirmed _ -> ReservationHoldConfirmation.builder()
                    .alreadyConfirmed(true)
                    .build();
            case HoldConfirmationResult.HoldNotFound _ -> {
                log.warn("Catedra does not know hold {}", holdId);
                throw new ReservationHoldNotFoundException(holdId);
            }
            case HoldConfirmationResult.HoldExpired _ -> throw new ReservationHoldExpiredException(holdId);
            // Los dos backends comparten la cuenta técnica: un hold de otra cuenta indica un error grave de integración
            case HoldConfirmationResult.OwnerMismatch _ -> {
                log.error("Catedra says hold {} belongs to another technical account", holdId);
                throw new ReservationIntegrationException("Hold owner mismatch");
            }
            case HoldConfirmationResult.PatientMismatch _ -> {
                log.warn("Catedra says the patient does not match the one of hold {}", holdId);
                throw new ReservationPatientMismatchException(holdId);
            }
            case HoldConfirmationResult.ProfessionalNotFound _ -> {
                log.warn("Catedra does not know professional {} while confirming hold {}", professionalId, holdId);
                throw new ReservationProfessionalNotFoundException(professionalId);
            }
            case HoldConfirmationResult.InvalidSlot _ -> {
                log.warn("Catedra rejected the slot of hold {} while confirming it", holdId);
                throw new ReservationInvalidSlotException(professionalId);
            }
            case HoldConfirmationResult.SlotNotAvailable _ -> {
                log.warn("Catedra says the slot of hold {} is already reserved", holdId);
                throw new ReservationSlotNotAvailableException(professionalId);
            }
            case HoldConfirmationResult.ConfigurationError error -> throw new ReservationServiceDegradedException(error.reason());
            case HoldConfirmationResult.Unavailable unavailable ->
                    throw new ReservationCentralUnavailableException(unavailable.reason());
            case HoldConfirmationResult.Failed failed -> {
                log.error("Catedra confirmation of hold {} failed: {}", holdId, failed.reason());
                throw new ReservationIntegrationException(failed.reason());
            }
        };
    }
}
