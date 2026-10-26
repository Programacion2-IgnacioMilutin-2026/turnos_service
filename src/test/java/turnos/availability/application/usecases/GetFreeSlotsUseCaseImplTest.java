package turnos.availability.application.usecases;

import org.junit.jupiter.api.Test;
import turnos.MutableClock;
import turnos.availability.application.exception.AvailabilityCatalogUnavailableException;
import turnos.availability.application.exception.AvailabilityCentralUnavailableException;
import turnos.availability.application.exception.AvailabilityDateInPastException;
import turnos.availability.application.exception.AvailabilityIntegrationException;
import turnos.availability.application.exception.AvailabilityProfessionalDisabledException;
import turnos.availability.application.exception.AvailabilityProfessionalNotFoundException;
import turnos.availability.application.exception.AvailabilityServiceDegradedException;
import turnos.availability.domain.model.AgendaSchedule;
import turnos.availability.domain.model.AvailabilityPolicy;
import turnos.availability.domain.model.EmptyReason;
import turnos.availability.domain.model.FreeSlot;
import turnos.availability.domain.model.FreeSlotsResult;
import turnos.availability.domain.model.OccupancySnapshot;
import turnos.availability.domain.model.ProfessionalAgenda;
import turnos.availability.domain.ports.out.OccupancyProvider;
import turnos.availability.domain.ports.out.ProfessionalAgendaProvider;
import turnos.availability.infrastructure.persistence.adapter.InMemoryOccupancyCacheAdapter;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetFreeSlotsUseCaseImplTest {

    // Domingo 25 a las 12:00 en Mendoza (UTC-3); la consulta es por el lunes 26
    private static final Instant START = Instant.parse("2026-10-25T15:00:00Z");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);
    private static final AvailabilityPolicy POLICY = new AvailabilityPolicy(ZoneId.of("America/Argentina/Mendoza"), Duration.ofMinutes(60));
    private static final ProfessionalAgenda MONDAY_MORNING = new ProfessionalAgenda(true, List.of(
            new AgendaSchedule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 30), 30, true)));

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryOccupancyCacheAdapter cache = new InMemoryOccupancyCacheAdapter(POLICY, clock);
    private final AtomicInteger agendaCalls = new AtomicInteger();
    private final AtomicInteger occupancyCalls = new AtomicInteger();
    private Supplier<ProfessionalAgenda> agenda = () -> MONDAY_MORNING;
    private Supplier<List<LocalTime>> occupancies = List::of;

    private final ProfessionalAgendaProvider agendaProvider = (professionalId, userToken) -> {
        agendaCalls.incrementAndGet();
        return agenda.get();
    };
    private final OccupancyProvider occupancyProvider = (professionalId, date) -> {
        occupancyCalls.incrementAndGet();
        return occupancies.get();
    };
    private final GetFreeSlotsUseCaseImpl useCase = new GetFreeSlotsUseCaseImpl(agendaProvider, occupancyProvider, cache, POLICY, clock);

    @Test
    void aDateBeforeTodayInTheAttentionZoneIsRejectedWithoutCallingAnyone() {
        assertThatThrownBy(() -> useCase.getFreeSlots(201L, LocalDate.of(2026, 10, 24), "user-jwt"))
                .isInstanceOf(AvailabilityDateInPastException.class);
        assertThat(agendaCalls).hasValue(0);
        assertThat(occupancyCalls).hasValue(0);
    }

    @Test
    void todayInTheAttentionZoneIsAcceptedEvenIfItIsAlreadyTomorrowInUtc() {
        clock.advance(Duration.ofHours(11)); // 02:00 UTC del lunes, todavía domingo 23:00 en Mendoza
        agenda = () -> new ProfessionalAgenda(true, List.of());

        assertThat(useCase.getFreeSlots(201L, LocalDate.of(2026, 10, 25), "user-jwt").getEmptyReason())
                .isEqualTo(EmptyReason.PROFESSIONAL_NOT_WORKING);
    }

    @Test
    void eachCatalogErrorPropagatesWithoutAskingForOccupancies() {
        for (RuntimeException error : List.of(new AvailabilityProfessionalNotFoundException(201L),
                new AvailabilityCatalogUnavailableException("down"), new AvailabilityIntegrationException("rejected"))) {
            agenda = () -> {
                throw error;
            };
            assertThatThrownBy(() -> useCase.getFreeSlots(201L, MONDAY, "user-jwt")).isSameAs(error);
        }
        assertThat(occupancyCalls).hasValue(0);
    }

    @Test
    void aDisabledProfessionalIsRejectedWithoutAskingForOccupancies() {
        agenda = () -> new ProfessionalAgenda(false, MONDAY_MORNING.getSchedules());

        assertThatThrownBy(() -> useCase.getFreeSlots(201L, MONDAY, "user-jwt"))
                .isInstanceOf(AvailabilityProfessionalDisabledException.class);
        assertThat(occupancyCalls).hasValue(0);
    }

    @Test
    void aProfessionalWhoDoesNotWorkThatDayAnswersWithoutAskingForOccupancies() {
        FreeSlotsResult result = useCase.getFreeSlots(201L, MONDAY.plusDays(1), "user-jwt");

        assertThat(result.getSlots()).isEmpty();
        assertThat(result.getEmptyReason()).isEqualTo(EmptyReason.PROFESSIONAL_NOT_WORKING);
        assertThat(result.getDataAsOf()).isEqualTo(START);
        assertThat(occupancyCalls).hasValue(0);
    }

    @Test
    void liveOccupanciesAreUsedAndSavedInTheCache() {
        occupancies = () -> List.of(LocalTime.of(9, 30));

        FreeSlotsResult result = useCase.getFreeSlots(201L, MONDAY, "user-jwt");

        assertThat(result.getSlots()).extracting(FreeSlot::getStartTime).containsExactly(LocalTime.of(9, 0), LocalTime.of(10, 0));
        assertThat(result.isStale()).isFalse();
        assertThat(result.getDataAsOf()).isEqualTo(START);
        assertThat(cache.find(201L, MONDAY)).get().extracting(OccupancySnapshot::getOccupiedStartTimes, OccupancySnapshot::getDataAsOf)
                .containsExactly(List.of(LocalTime.of(9, 30)), START);
    }

    @Test
    void anUnavailableCatedraUsesAValidCacheEntryAsStale() {
        cache.save(201L, MONDAY, new OccupancySnapshot(List.of(LocalTime.of(9, 0)), START));
        clock.advance(Duration.ofMinutes(60));
        occupancies = () -> {
            throw new AvailabilityCentralUnavailableException("down");
        };

        FreeSlotsResult result = useCase.getFreeSlots(201L, MONDAY, "user-jwt");

        assertThat(result.getSlots()).extracting(FreeSlot::getStartTime).containsExactly(LocalTime.of(9, 30), LocalTime.of(10, 0));
        assertThat(result.isStale()).isTrue();
        assertThat(result.getDataAsOf()).isEqualTo(START);
    }

    @Test
    void anExpiredCacheEntryIsRemovedAndTheCatedraIsUnavailable() {
        cache.save(201L, MONDAY, new OccupancySnapshot(List.of(), START));
        clock.advance(Duration.ofMinutes(61));
        occupancies = () -> {
            throw new AvailabilityCentralUnavailableException("down");
        };

        assertThatThrownBy(() -> useCase.getFreeSlots(201L, MONDAY, "user-jwt"))
                .isInstanceOf(AvailabilityCentralUnavailableException.class);
        assertThat(cache.find(201L, MONDAY)).isEmpty();
    }

    @Test
    void withoutACacheEntryTheCatedraIsUnavailable() {
        occupancies = () -> {
            throw new AvailabilityCentralUnavailableException("down");
        };

        assertThatThrownBy(() -> useCase.getFreeSlots(201L, MONDAY, "user-jwt"))
                .isInstanceOf(AvailabilityCentralUnavailableException.class);
    }

    @Test
    void aDegradedIntegrationOrAnUnexpectedAnswerNeverUsesTheCache() {
        cache.save(201L, MONDAY, new OccupancySnapshot(List.of(), START));
        for (RuntimeException error : List.of(new AvailabilityServiceDegradedException("config"),
                new AvailabilityIntegrationException("HTTP 400"))) {
            occupancies = () -> {
                throw error;
            };
            assertThatThrownBy(() -> useCase.getFreeSlots(201L, MONDAY, "user-jwt")).isSameAs(error);
        }
    }

    @Test
    void whenEverySlotIsTakenThereAreNoFreeSlots() {
        occupancies = () -> List.of(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0));

        assertThat(useCase.getFreeSlots(201L, MONDAY, "user-jwt").getEmptyReason()).isEqualTo(EmptyReason.NO_FREE_SLOTS);
    }
}
