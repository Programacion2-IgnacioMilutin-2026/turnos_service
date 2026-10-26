package turnos.catalog.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.List;

// Catálogo devuelve más datos (nombre, categoría, ids de los horarios); se leen solo los que se usan
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfessionalResponse {
    private Long id;
    private boolean enabled;
    private List<WeeklyScheduleResponse> weeklySchedules;
}
