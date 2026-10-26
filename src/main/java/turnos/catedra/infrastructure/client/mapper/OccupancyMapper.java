package turnos.catedra.infrastructure.client.mapper;

import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.Occupancy;
import turnos.catedra.domain.model.OccupancyStatus;
import turnos.catedra.infrastructure.client.dto.OccupancyResponse;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class OccupancyMapper {

    // Un estado ausente o que no es CONFIRMED ni HELD lanza IllegalArgumentException: es una respuesta no prevista
    public List<Occupancy> toDomain(List<OccupancyResponse> responses) {
        return responses.stream()
                .map(response -> Occupancy.builder()
                        .startTime(response.getStartTime())
                        .status(status(response.getStatus()))
                        .build())
                .collect(Collectors.toList());
    }

    private static OccupancyStatus status(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Occupancy without status");
        }
        return OccupancyStatus.valueOf(value);
    }
}
