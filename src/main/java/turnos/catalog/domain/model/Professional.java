package turnos.catalog.domain.model;

import lombok.*;

import java.util.List;

// Solo lo que usan los casos de uso que consultan catálogo
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Professional {
    private Long id;
    private String firstName;
    private String lastName;
    private String categoryName;
    private boolean enabled;
    private List<WeeklySchedule> weeklySchedules;
}
