package turnos.availability.infrastructure.web.mapper;

import org.junit.jupiter.api.Test;
import turnos.availability.domain.model.FreeSlot;
import turnos.availability.domain.model.FreeSlotsResult;
import turnos.availability.infrastructure.web.dto.FreeSlotResponse;
import turnos.availability.infrastructure.web.dto.FreeSlotsResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityDtoMapperTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 26);

    private final AvailabilityDtoMapper mapper = new AvailabilityDtoMapper();

    @Test
    void eachSlotRepeatsTheProfessionalAndTheDate() {
        FreeSlotsResponse response = mapper.toResponse(201L, DATE, FreeSlotsResult.of(
                List.of(new FreeSlot(LocalTime.of(9, 5), 20)), false, Instant.parse("2026-10-25T15:00:12.999Z")));

        assertThat(response.getSlots()).singleElement().usingRecursiveComparison()
                .isEqualTo(new FreeSlotResponse(201L, DATE, "09:05", 20));
        assertThat(response.getEmptyReason()).isNull();
        assertThat(response.isStale()).isFalse();
        assertThat(response.getDataAsOf()).isEqualTo(Instant.parse("2026-10-25T15:00:12Z"));
    }

    @Test
    void theEmptyReasonIsItsName() {
        assertThat(mapper.toResponse(201L, DATE, FreeSlotsResult.notWorking(Instant.EPOCH)).getEmptyReason())
                .isEqualTo("PROFESSIONAL_NOT_WORKING");
    }
}
