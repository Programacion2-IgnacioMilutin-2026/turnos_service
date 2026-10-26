package turnos.availability.infrastructure.persistence.adapter;

import org.junit.jupiter.api.Test;
import turnos.MutableClock;
import turnos.availability.domain.model.AvailabilityPolicy;
import turnos.availability.domain.model.OccupancySnapshot;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryOccupancyCacheAdapterTest {

    private static final Instant START = Instant.parse("2026-10-25T15:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 26);

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryOccupancyCacheAdapter cache = new InMemoryOccupancyCacheAdapter(
            new AvailabilityPolicy(ZoneId.of("America/Argentina/Mendoza"), Duration.ofMinutes(60)), clock);

    @Test
    void anEntryIsKeptPerProfessionalAndDateAndReplaced() {
        cache.save(201L, DATE, snapshot(START));
        cache.save(201L, DATE.plusDays(1), snapshot(START));
        OccupancySnapshot newer = snapshot(START.plusSeconds(30));
        cache.save(201L, DATE, newer);

        assertThat(cache.find(201L, DATE)).containsSame(newer);
        assertThat(cache.find(202L, DATE)).isEmpty();
        cache.remove(201L, DATE);
        assertThat(cache.find(201L, DATE)).isEmpty();
        assertThat(cache.find(201L, DATE.plusDays(1))).isPresent();
    }

    @Test
    void theSweepRemovesOnlyExpiredEntries() {
        cache.save(201L, DATE, snapshot(START));
        cache.save(202L, DATE, snapshot(START.plus(Duration.ofMinutes(30))));
        clock.advance(Duration.ofMinutes(61));

        cache.removeExpired();

        assertThat(cache.find(201L, DATE)).isEmpty();
        assertThat(cache.find(202L, DATE)).isPresent();
    }

    @Test
    void manyThreadsCanReadAndSaveAtTheSameTime() throws Exception {
        List<Future<?>> tasks = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
            for (int thread = 0; thread < 20; thread++) {
                long professionalId = thread % 5;
                tasks.add(executor.submit(() -> {
                    for (int i = 0; i < 500; i++) {
                        cache.save(professionalId, DATE, snapshot(START));
                        cache.find(professionalId, DATE);
                        cache.removeExpired();
                    }
                }));
            }
            for (Future<?> task : tasks) {
                task.get();
            }
        }
        for (long professionalId = 0; professionalId < 5; professionalId++) {
            assertThat(cache.find(professionalId, DATE)).isPresent();
        }
    }

    private static OccupancySnapshot snapshot(Instant dataAsOf) {
        return new OccupancySnapshot(List.of(LocalTime.of(9, 0)), dataAsOf);
    }
}
