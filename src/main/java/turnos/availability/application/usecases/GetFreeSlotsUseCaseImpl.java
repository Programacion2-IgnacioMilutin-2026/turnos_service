package turnos.availability.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.availability.application.exception.AvailabilityCentralUnavailableException;
import turnos.availability.application.exception.AvailabilityDateInPastException;
import turnos.availability.application.exception.AvailabilityProfessionalDisabledException;
import turnos.availability.domain.model.AvailabilityPolicy;
import turnos.availability.domain.model.FreeSlotsResult;
import turnos.availability.domain.model.OccupancySnapshot;
import turnos.availability.domain.model.ProfessionalAgenda;
import turnos.availability.domain.ports.in.GetFreeSlotsUseCase;
import turnos.availability.domain.ports.out.OccupancyCache;
import turnos.availability.domain.ports.out.OccupancyProvider;
import turnos.availability.domain.ports.out.ProfessionalAgendaProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetFreeSlotsUseCaseImpl implements GetFreeSlotsUseCase {

    private final ProfessionalAgendaProvider professionalAgendaProvider;
    private final OccupancyProvider occupancyProvider;
    private final OccupancyCache occupancyCache;
    private final AvailabilityPolicy availabilityPolicy;
    private final Clock clock;

    // Primero lo que se resuelve sin la cátedra: la fecha, el profesional y si atiende ese día
    @Override
    public FreeSlotsResult getFreeSlots(Long professionalId, LocalDate date, String userToken) {
        Instant requestedAt = clock.instant();
        LocalDateTime now = LocalDateTime.ofInstant(requestedAt, availabilityPolicy.zone());
        if (date.isBefore(now.toLocalDate())) {
            throw new AvailabilityDateInPastException(date);
        }
        ProfessionalAgenda agenda = professionalAgendaProvider.getAgenda(professionalId, userToken);
        if (!agenda.isEnabled()) {
            throw new AvailabilityProfessionalDisabledException(professionalId);
        }
        if (!agenda.worksOn(date)) {
            return FreeSlotsResult.notWorking(requestedAt);
        }
        OccupancySnapshot snapshot;
        boolean stale;
        try {
            List<LocalTime> occupied = occupancyProvider.getOccupiedStartTimes(professionalId, date);
            snapshot = new OccupancySnapshot(List.copyOf(occupied), clock.instant());
            occupancyCache.save(professionalId, date, snapshot);
            stale = false;
        } catch (AvailabilityCentralUnavailableException e) {
            snapshot = cachedOrRethrow(professionalId, date, e);
            stale = true;
        }
        return FreeSlotsResult.of(agenda.freeSlots(date, now, snapshot.getOccupiedStartTimes()), stale, snapshot.getDataAsOf());
    }

    // Solo cuando la cátedra no está disponible se recurre a la última respuesta guardada, si no superó la antigüedad máxima
    private OccupancySnapshot cachedOrRethrow(Long professionalId, LocalDate date, AvailabilityCentralUnavailableException cause) {
        Optional<OccupancySnapshot> cached = occupancyCache.find(professionalId, date);
        if (cached.isEmpty()) {
            throw cause;
        }
        if (cached.get().isOlderThan(availabilityPolicy.maxCacheAge(), clock.instant())) {
            occupancyCache.remove(professionalId, date);
            throw cause;
        }
        log.warn("Catedra unavailable, using cached occupancies of professional {} on {} from {}",
                professionalId, date, cached.get().getDataAsOf());
        return cached.get();
    }
}
