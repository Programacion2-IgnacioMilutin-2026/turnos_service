package turnos.reservation.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catalog.domain.model.Professional;
import turnos.catalog.domain.model.ProfessionalResult;
import turnos.catalog.domain.ports.in.GetProfessionalUseCase;
import turnos.reservation.application.exception.ReservationCatalogUnavailableException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationProfessionalNotFoundException;
import turnos.reservation.domain.model.ReservationProfessional;
import turnos.reservation.domain.model.ReservationSchedule;
import turnos.reservation.domain.ports.out.ProfessionalProvider;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Único lugar de reservation que conoce a catalog: entra por su puerto de entrada y traduce lo que devuelve
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogProfessionalAdapter implements ProfessionalProvider {

    private final GetProfessionalUseCase getProfessionalUseCase;

    @Override
    public ReservationProfessional getProfessional(Long professionalId, String userToken) {
        return switch (getProfessionalUseCase.getProfessional(professionalId, userToken)) {
            case ProfessionalResult.Found found -> toReservationProfessional(found.professional());
            case ProfessionalResult.NotFound _ -> throw new ReservationProfessionalNotFoundException(professionalId);
            case ProfessionalResult.Unavailable unavailable -> throw new ReservationCatalogUnavailableException(unavailable.reason());
            case ProfessionalResult.Rejected rejected -> {
                log.error("Catalog rejected the forwarded user token with HTTP {}", rejected.status());
                throw new ReservationIntegrationException("Catalog rejected the forwarded user token");
            }
            case ProfessionalResult.Failed failed -> {
                log.error("Catalog request for professional {} failed: {}", professionalId, failed.reason());
                throw new ReservationIntegrationException(failed.reason());
            }
        };
    }

    private static ReservationProfessional toReservationProfessional(Professional professional) {
        return ReservationProfessional.builder()
                .enabled(professional.isEnabled())
                .firstName(professional.getFirstName())
                .lastName(professional.getLastName())
                .categoryName(professional.getCategoryName())
                .schedules(professional.getWeeklySchedules().stream()
                        .map(schedule -> ReservationSchedule.builder()
                                .dayOfWeek(schedule.getDayOfWeek())
                                .startTime(schedule.getStartTime())
                                .endTime(schedule.getEndTime())
                                .slotDurationMinutes(schedule.getSlotDurationMinutes())
                                .enabled(schedule.isEnabled())
                                .build())
                        .toList())
                .build();
    }
}
