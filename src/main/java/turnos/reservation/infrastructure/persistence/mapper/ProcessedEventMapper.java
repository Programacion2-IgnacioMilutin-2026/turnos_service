package turnos.reservation.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;
import turnos.reservation.domain.model.ProcessedEvent;
import turnos.reservation.infrastructure.persistence.entity.ProcessedEventEntity;

@Component
public class ProcessedEventMapper {

    public ProcessedEventEntity toEntity(ProcessedEvent processedEvent) {
        if (processedEvent == null) {
            return null;
        }
        return ProcessedEventEntity.builder()
                .eventId(processedEvent.getEventId())
                .processedAt(processedEvent.getProcessedAt())
                .build();
    }

    public ProcessedEvent toDomainModel(ProcessedEventEntity entity) {
        if (entity == null) {
            return null;
        }
        return ProcessedEvent.builder()
                .eventId(entity.getEventId())
                .processedAt(entity.getProcessedAt())
                .build();
    }
}
