package turnos.reservation.domain.ports.out;

import turnos.reservation.domain.model.ProcessedEvent;

public interface ProcessedEventRepository {

    ProcessedEvent create(ProcessedEvent processedEvent);
    boolean existsById(String eventId);

}
