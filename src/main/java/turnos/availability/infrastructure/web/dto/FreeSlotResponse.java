package turnos.availability.infrastructure.web.dto;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreeSlotResponse {
    private Long professionalId;
    private LocalDate date;
    // HH:mm, sin segundos
    private String startTime;
    private int durationMinutes;
}
