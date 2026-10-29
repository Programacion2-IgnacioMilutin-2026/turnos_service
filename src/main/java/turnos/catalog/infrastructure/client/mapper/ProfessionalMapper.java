package turnos.catalog.infrastructure.client.mapper;

import org.springframework.stereotype.Component;
import turnos.catalog.domain.model.Professional;
import turnos.catalog.domain.model.WeeklySchedule;
import turnos.catalog.infrastructure.client.dto.ProfessionalResponse;
import turnos.catalog.infrastructure.client.dto.WeeklyScheduleResponse;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ProfessionalMapper {

    public Professional toDomain(ProfessionalResponse response) {
        if (response == null) {
            return null;
        }
        return Professional.builder()
                .id(response.getId())
                .firstName(response.getFirstName())
                .lastName(response.getLastName())
                .categoryName(response.getCategory() == null ? null : response.getCategory().getName())
                .enabled(response.isEnabled())
                .weeklySchedules(toDomain(response.getWeeklySchedules()))
                .build();
    }

    // Un profesional sin horarios llega con la lista vacía; si faltara, también se trata como vacía
    private List<WeeklySchedule> toDomain(List<WeeklyScheduleResponse> responses) {
        if (responses == null) {
            return List.of();
        }
        return responses.stream()
                .map(response -> WeeklySchedule.builder()
                        .dayOfWeek(response.getDayOfWeek())
                        .startTime(response.getStartTime())
                        .endTime(response.getEndTime())
                        .slotDurationMinutes(response.getSlotDurationMinutes())
                        .enabled(response.isEnabled())
                        .build())
                .collect(Collectors.toList());
    }
}
