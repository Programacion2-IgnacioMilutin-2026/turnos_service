package turnos.catedra.infrastructure.client.mapper;

import org.junit.jupiter.api.Test;
import turnos.catedra.domain.model.Hold;
import turnos.catedra.infrastructure.client.dto.HoldResponse;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HoldMapperTest {

    private final HoldMapper mapper = new HoldMapper();

    @Test
    void everyFieldReachesTheDomain() {
        HoldResponse response = HoldResponse.builder().holdId("hold-1").reservationProcessId("process-1").status("HELD")
                .expiresAt(Instant.parse("2026-10-27T04:44:38.627744064Z")).build();
        assertThat(response).hasNoNullFieldsOrProperties();

        Hold hold = mapper.toDomain(response);

        assertThat(hold).hasNoNullFieldsOrProperties();
        assertThat(hold).usingRecursiveComparison().isEqualTo(response);
    }

    @Test
    void nullMapsToNull() {
        assertThat(mapper.toDomain(null)).isNull();
    }
}
