package turnos.availability.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.availability.application.exception.AvailabilityCatalogUnavailableException;
import turnos.availability.application.exception.AvailabilityIntegrationException;
import turnos.availability.application.exception.AvailabilityProfessionalNotFoundException;
import turnos.availability.domain.model.AgendaSchedule;
import turnos.availability.domain.model.ProfessionalAgenda;
import turnos.availability.domain.ports.out.ProfessionalAgendaProvider;
import turnos.catalog.domain.model.Professional;
import turnos.catalog.domain.model.ProfessionalResult;
import turnos.catalog.domain.ports.in.GetProfessionalUseCase;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Único lugar de availability que conoce a catalog: entra por su puerto de entrada y traduce lo que devuelve
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogAgendaAdapter implements ProfessionalAgendaProvider {

    private final GetProfessionalUseCase getProfessionalUseCase;

    @Override
    public ProfessionalAgenda getAgenda(Long professionalId, String userToken) {
        return switch (getProfessionalUseCase.getProfessional(professionalId, userToken)) {
            case ProfessionalResult.Found found -> toAgenda(found.professional());
            case ProfessionalResult.NotFound _ -> throw new AvailabilityProfessionalNotFoundException(professionalId);
            case ProfessionalResult.Unavailable unavailable -> throw new AvailabilityCatalogUnavailableException(unavailable.reason());
            case ProfessionalResult.Rejected rejected -> {
                log.error("Catalog rejected the forwarded user token with HTTP {}", rejected.status());
                throw new AvailabilityIntegrationException("Catalog rejected the forwarded user token");
            }
            case ProfessionalResult.Failed failed -> {
                log.error("Catalog request for professional {} failed: {}", professionalId, failed.reason());
                throw new AvailabilityIntegrationException(failed.reason());
            }
        };
    }

    private static ProfessionalAgenda toAgenda(Professional professional) {
        return ProfessionalAgenda.builder()
                .enabled(professional.isEnabled())
                .schedules(professional.getWeeklySchedules().stream()
                        .map(schedule -> AgendaSchedule.builder()
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
