package turnos.catedra.infrastructure.client.mapper;

import org.junit.jupiter.api.Test;
import turnos.catedra.domain.model.Occupancy;
import turnos.catedra.domain.model.OccupancyStatus;
import turnos.catedra.infrastructure.client.dto.OccupancyResponse;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OccupancyMapperTest {

    private final OccupancyMapper mapper = new OccupancyMapper();

    @Test
    void everyFieldReachesTheDomain() {
        OccupancyResponse response = OccupancyResponse.builder().startTime(LocalTime.of(12, 30)).status("HELD").build();
        assertThat(response).hasNoNullFieldsOrProperties();

        Occupancy occupancy = mapper.toDomain(List.of(response)).getFirst();

        assertThat(occupancy.getStartTime()).isEqualTo(LocalTime.of(12, 30));
        assertThat(occupancy.getStatus()).isEqualTo(OccupancyStatus.HELD);
    }

    @Test
    void anUnknownOrMissingStatusIsRejected() {
        OccupancyResponse unknown = OccupancyResponse.builder().startTime(LocalTime.NOON).status("CANCELLED").build();
        OccupancyResponse missing = OccupancyResponse.builder().startTime(LocalTime.NOON).build();

        assertThatThrownBy(() -> mapper.toDomain(List.of(unknown))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.toDomain(List.of(missing))).isInstanceOf(IllegalArgumentException.class);
    }
}
