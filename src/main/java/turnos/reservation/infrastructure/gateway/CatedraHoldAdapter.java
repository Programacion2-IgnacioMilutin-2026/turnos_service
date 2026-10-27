package turnos.reservation.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.HoldResult;
import turnos.catedra.domain.ports.in.CreateHoldUseCase;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationInvalidSlotException;
import turnos.reservation.application.exception.ReservationProfessionalDisabledException;
import turnos.reservation.application.exception.ReservationProfessionalNotFoundException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.application.exception.ReservationSlotNotAvailableException;
import turnos.reservation.domain.model.ReservationHold;
import turnos.reservation.domain.ports.out.HoldProvider;

import java.time.LocalDate;
import java.time.LocalTime;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Único lugar de reservation que conoce a catedra: entra por su puerto de entrada y traduce lo que devuelve
@Slf4j
@Component
@RequiredArgsConstructor
public class CatedraHoldAdapter implements HoldProvider {

    private final CreateHoldUseCase createHoldUseCase;

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
}
