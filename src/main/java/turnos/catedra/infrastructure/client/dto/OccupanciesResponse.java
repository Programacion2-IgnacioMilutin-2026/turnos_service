package turnos.catedra.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.List;

// La cátedra repite el profesional y las fechas consultadas; se leen solo las ocupaciones
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OccupanciesResponse {
    private List<OccupancyResponse> occupancies;
}
