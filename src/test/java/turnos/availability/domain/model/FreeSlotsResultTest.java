package turnos.availability.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FreeSlotsResultTest {

    private static final Instant NOW = Instant.parse("2026-10-25T15:00:00Z");

    @Test
    void slotsLeftHaveNoEmptyReason() {
        FreeSlotsResult result = FreeSlotsResult.of(List.of(new FreeSlot(LocalTime.of(9, 0), 30)), false, NOW);

        assertThat(result.getSlots()).hasSize(1);
        assertThat(result.getEmptyReason()).isNull();
        assertThat(result.isStale()).isFalse();
        assertThat(result.getDataAsOf()).isEqualTo(NOW);
    }

    @Test
    void noSlotsLeftMeansNoFreeSlots() {
        FreeSlotsResult result = FreeSlotsResult.of(List.of(), true, NOW);

        assertThat(result.getSlots()).isEmpty();
        assertThat(result.getEmptyReason()).isEqualTo(EmptyReason.NO_FREE_SLOTS);
        assertThat(result.isStale()).isTrue();
    }

    @Test
    void aProfessionalWhoDoesNotWorkThatDayHasItsOwnReason() {
        FreeSlotsResult result = FreeSlotsResult.notWorking(NOW);

        assertThat(result.getSlots()).isEmpty();
        assertThat(result.getEmptyReason()).isEqualTo(EmptyReason.PROFESSIONAL_NOT_WORKING);
        assertThat(result.isStale()).isFalse();
        assertThat(result.getDataAsOf()).isEqualTo(NOW);
    }

    @Test
    void aSnapshotExpiresOnlyAfterTheMaximumAge() {
        OccupancySnapshot snapshot = new OccupancySnapshot(List.of(), NOW);
        Duration maxAge = Duration.ofMinutes(60);

        assertThat(snapshot.isOlderThan(maxAge, NOW.plus(maxAge))).isFalse();
        assertThat(snapshot.isOlderThan(maxAge, NOW.plus(maxAge).plusSeconds(1))).isTrue();
    }
}
