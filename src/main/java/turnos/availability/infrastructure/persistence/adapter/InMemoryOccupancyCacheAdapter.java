package turnos.availability.infrastructure.persistence.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import turnos.availability.domain.model.AvailabilityPolicy;
import turnos.availability.domain.model.OccupancySnapshot;
import turnos.availability.domain.ports.out.OccupancyCache;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Caché en memoria, sin base de datos: excepción a la skill autorizada por el usuario.
// Se pierde al reiniciar; es un dato auxiliar, no de negocio
@Component
@RequiredArgsConstructor
public class InMemoryOccupancyCacheAdapter implements OccupancyCache {

    private record Key(Long professionalId, LocalDate date) {
    }

    private final Map<Key, OccupancySnapshot> entries = new ConcurrentHashMap<>();
    private final AvailabilityPolicy availabilityPolicy;
    private final Clock clock;

    @Override
    public Optional<OccupancySnapshot> find(Long professionalId, LocalDate date) {
        return Optional.ofNullable(entries.get(new Key(professionalId, date)));
    }

    @Override
    public void save(Long professionalId, LocalDate date, OccupancySnapshot snapshot) {
        entries.put(new Key(professionalId, date), snapshot);
    }

    @Override
    public void remove(Long professionalId, LocalDate date) {
        entries.remove(new Key(professionalId, date));
    }

    // Las entradas vencidas se borran solas, aunque nadie vuelva a consultarlas
    @Scheduled(fixedRate = 60_000)
    public void removeExpired() {
        entries.values().removeIf(snapshot -> snapshot.isOlderThan(availabilityPolicy.maxCacheAge(), clock.instant()));
    }
}
