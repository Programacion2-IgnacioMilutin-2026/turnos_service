package turnos.reservation.infrastructure.persistence.mapper;

import org.junit.jupiter.api.Test;
import turnos.reservation.domain.model.ProcessedEvent;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedEventMapperTest {

    private final ProcessedEventMapper mapper = new ProcessedEventMapper();

    @Test
    void roundTripKeepsEveryField() {
        ProcessedEvent original = ProcessedEvent.builder()
                .eventId("request-event-1")
                .processedAt(Instant.parse("2026-10-30T12:00:00Z"))
                .build();
        assertThat(original).hasNoNullFieldsOrProperties();

        ProcessedEvent result = mapper.toDomainModel(mapper.toEntity(original));

        assertThat(result).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void aNewEntityIsMarkedAsNew() {
        assertThat(mapper.toEntity(ProcessedEvent.builder().eventId("request-event-1").build()).isNew()).isTrue();
    }

    @Test
    void nullMapsToNull() {
        assertThat(mapper.toEntity(null)).isNull();
        assertThat(mapper.toDomainModel(null)).isNull();
    }
}
