package turnos.catalog.infrastructure.client.mapper;

import org.junit.jupiter.api.Test;
import turnos.catalog.domain.model.Professional;
import turnos.catalog.infrastructure.client.dto.ProfessionalResponse;
import turnos.catalog.infrastructure.client.dto.WeeklyScheduleResponse;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfessionalMapperTest {

    private final ProfessionalMapper mapper = new ProfessionalMapper();

    @Test
    void everyFieldReachesTheDomain() {
        WeeklyScheduleResponse schedule = WeeklyScheduleResponse.builder().dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).slotDurationMinutes(30).enabled(true).build();
        ProfessionalResponse response = ProfessionalResponse.builder().id(15L).enabled(true)
                .weeklySchedules(List.of(schedule)).build();
        assertThat(response).hasNoNullFieldsOrProperties();
        assertThat(schedule).hasNoNullFieldsOrProperties();

        Professional professional = mapper.toDomain(response);

        assertThat(professional).usingRecursiveComparison().isEqualTo(response);
    }

    @Test
    void missingSchedulesBecomeAnEmptyList() {
        assertThat(mapper.toDomain(ProfessionalResponse.builder().id(15L).build()).getWeeklySchedules()).isEmpty();
    }

    @Test
    void nullMapsToNull() {
        assertThat(mapper.toDomain(null)).isNull();
    }
}
