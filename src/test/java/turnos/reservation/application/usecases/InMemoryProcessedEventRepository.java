package turnos.reservation.application.usecases;

import turnos.reservation.domain.model.ProcessedEvent;
import turnos.reservation.domain.ports.out.ProcessedEventRepository;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Los eventos registrados, con el instante en que se procesaron
final class InMemoryProcessedEventRepository implements ProcessedEventRepository {

    private final Map<String, Instant> registered = new ConcurrentHashMap<>();

    Map<String, Instant> registered() {
        return registered;
    }

    @Override
    public ProcessedEvent create(ProcessedEvent processedEvent) {
        registered.put(processedEvent.getEventId(), processedEvent.getProcessedAt());
        return processedEvent;
    }

    @Override
    public boolean existsById(String eventId) {
        return registered.containsKey(eventId);
    }
}
