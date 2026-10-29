package turnos.catalog.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.List;

// Catálogo devuelve más datos (ids y estado de la categoría, ids de los horarios); se leen solo los que se usan
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfessionalResponse {
    private Long id;
    private String firstName;
    private String lastName;
    private CategoryResponse category;
    private boolean enabled;
    private List<WeeklyScheduleResponse> weeklySchedules;
}
