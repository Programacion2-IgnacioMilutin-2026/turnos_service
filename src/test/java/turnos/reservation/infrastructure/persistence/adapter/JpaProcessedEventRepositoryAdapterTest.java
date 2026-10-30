package turnos.reservation.infrastructure.persistence.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import turnos.TestcontainersConfiguration;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.domain.model.ProcessedEvent;
import turnos.reservation.domain.ports.out.ProcessedEventRepository;
import turnos.reservation.infrastructure.persistence.mapper.ProcessedEventMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({TestcontainersConfiguration.class, JpaProcessedEventRepositoryAdapter.class, ProcessedEventMapper.class})
class JpaProcessedEventRepositoryAdapterTest {

    private static final Instant PROCESSED_AT = Instant.parse("2026-10-30T12:00:00Z");

    @Autowired
    private ProcessedEventRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void aRegisteredEventIsFoundAndAnotherIsNot() {
        ProcessedEvent saved = repository.create(new ProcessedEvent("request-event-1", PROCESSED_AT));
        entityManager.clear();

        assertThat(saved).usingRecursiveComparison().isEqualTo(new ProcessedEvent("request-event-1", PROCESSED_AT));
        assertThat(repository.existsById("request-event-1")).isTrue();
        assertThat(repository.existsById("request-event-2")).isFalse();
    }

    @Test
    void theInstantIsSavedAsIs() {
        repository.create(new ProcessedEvent("request-event-1", PROCESSED_AT));
        entityManager.clear();

        assertThat(entityManager.getEntityManager()
                .createNativeQuery("SELECT count(*) FROM processed_events WHERE processed_at = TIMESTAMPTZ '2026-10-30T12:00:00Z'")
                .getSingleResult()).isEqualTo(1L);
    }

    @Test
    void theSameEventCannotBeRegisteredTwice() {
        repository.create(new ProcessedEvent("request-event-1", PROCESSED_AT));
        entityManager.clear();

        assertThatThrownBy(() -> repository.create(new ProcessedEvent("request-event-1", PROCESSED_AT.plusSeconds(5))))
                .isInstanceOf(ReservationNotSavedException.class)
                .hasStackTraceContaining("processed_events_pkey");
    }
}
