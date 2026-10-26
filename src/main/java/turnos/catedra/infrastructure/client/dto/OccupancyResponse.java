package turnos.catedra.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.LocalTime;

// La hora llega como HH:mm:ss o HH:mm; LocalTime acepta las dos. La fecha y el vencimiento del hold no se usan
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OccupancyResponse {
    private LocalTime startTime;
    private String status;
}
