package turnos.reservation.infrastructure.persistence.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.domain.model.ProcessedEvent;
import turnos.reservation.domain.ports.out.ProcessedEventRepository;
import turnos.reservation.infrastructure.persistence.entity.ProcessedEventEntity;
import turnos.reservation.infrastructure.persistence.mapper.ProcessedEventMapper;
import turnos.reservation.infrastructure.persistence.repository.JpaProcessedEventRepository;

@Component
@RequiredArgsConstructor
public class JpaProcessedEventRepositoryAdapter implements ProcessedEventRepository {

    private final JpaProcessedEventRepository jpaProcessedEventRepository;
    private final ProcessedEventMapper processedEventMapper;

    // Con flush, para que un evento ya registrado choque con la clave primaria acá y no al confirmar la transacción
    @Override
    public ProcessedEvent create(ProcessedEvent processedEvent) {
        ProcessedEventEntity entity = processedEventMapper.toEntity(processedEvent);
        try {
            return processedEventMapper.toDomainModel(jpaProcessedEventRepository.saveAndFlush(entity));
        } catch (DataAccessException e) {
            throw new ReservationNotSavedException(e);
        }
    }

    @Override
    public boolean existsById(String eventId) {
        return jpaProcessedEventRepository.existsById(eventId);
    }
}
