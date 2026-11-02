package turnos.reservation.infrastructure.persistence.mapper;

import org.junit.jupiter.api.Test;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationProcessMapperTest {

    private final ReservationProcessMapper mapper = new ReservationProcessMapper();

    @Test
    void roundTripKeepsEveryField() {
        ReservationProcess original = ReservationProcess.builder()
                .reservationProcessId("process-1")
                .holdId("hold-1")
                .userId(42L)
                .professionalId(201L)
                .professionalFirstName("Ana")
                .professionalLastName("Gomez")
                .professionalCategoryName("Clinica medica")
                .date(LocalDate.of(2026, 11, 2))
                .startTime(LocalTime.of(12, 0))
                .status(ReservationStatus.HELD)
                .expiresAt(Instant.parse("2026-10-27T04:44:38Z"))
                .needsReconciliation(true)
                .requestEventId("request-event-1")
                .build();
        assertThat(original).hasNoNullFieldsOrProperties();

        ReservationProcess result = mapper.toDomainModel(mapper.toEntity(original));

        assertThat(result).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void aNewEntityIsMarkedAsNew() {
        assertThat(mapper.toEntity(ReservationProcess.builder().reservationProcessId("process-1").build()).isNew()).isTrue();
    }

    @Test
    void nullMapsToNull() {
        assertThat(mapper.toEntity(null)).isNull();
        assertThat(mapper.toDomainModel(null)).isNull();
    }
}
