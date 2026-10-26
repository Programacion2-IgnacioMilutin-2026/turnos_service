package turnos.availability.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.availability.application.exception.AvailabilityCentralUnavailableException;
import turnos.availability.application.exception.AvailabilityIntegrationException;
import turnos.availability.application.exception.AvailabilityServiceDegradedException;
import turnos.availability.domain.ports.out.OccupancyProvider;
import turnos.catedra.domain.model.OccupanciesResult;
import turnos.catedra.domain.model.Occupancy;
import turnos.catedra.domain.ports.in.GetOccupanciesUseCase;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Único lugar de availability que conoce a catedra: entra por su puerto de entrada y traduce lo que devuelve.
// Confirmado o retenido, un turno ocupa igual: solo importa su hora de inicio
@Slf4j
@Component
@RequiredArgsConstructor
public class CatedraOccupancyAdapter implements OccupancyProvider {

    private final GetOccupanciesUseCase getOccupanciesUseCase;

    @Override
    public List<LocalTime> getOccupiedStartTimes(Long professionalId, LocalDate date) {
        return switch (getOccupanciesUseCase.getOccupancies(professionalId, date)) {
            case OccupanciesResult.Found found -> found.occupancies().stream().map(Occupancy::getStartTime).toList();
            case OccupanciesResult.ConfigurationError error -> throw new AvailabilityServiceDegradedException(error.reason());
            case OccupanciesResult.Unavailable unavailable -> throw new AvailabilityCentralUnavailableException(unavailable.reason());
            // El caso de uso de catedra renueva la sesión ante un rechazo; si igual llegara uno, la cátedra no está disponible
            case OccupanciesResult.TokenRejected _ -> throw new AvailabilityCentralUnavailableException("Technical token rejected");
            case OccupanciesResult.Failed failed -> {
                log.error("Catedra occupancies request for professional {} failed: {}", professionalId, failed.reason());
                throw new AvailabilityIntegrationException(failed.reason());
            }
        };
    }
}
