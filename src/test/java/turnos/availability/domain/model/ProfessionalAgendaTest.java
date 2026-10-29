package turnos.availability.domain.model;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class ProfessionalAgendaTest {

    // Un lunes; la consulta se hace el día anterior, así que ningún turno comenzó
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);
    private static final LocalDateTime DAY_BEFORE = MONDAY.minusDays(1).atTime(18, 0);

    @Test
    void aScheduleGeneratesEverySlotThatEndsInTime() {
        ProfessionalAgenda agenda = agenda(schedule(DayOfWeek.MONDAY, "09:00", "12:00", 30, true));

        assertThat(startTimes(agenda.freeSlots(MONDAY, DAY_BEFORE, List.of())))
                .containsExactly("09:00", "09:30", "10:00", "10:30", "11:00", "11:30");
    }

    @Test
    void aSlotThatWouldEndAfterTheScheduleIsNotGenerated() {
        ProfessionalAgenda agenda = agenda(schedule(DayOfWeek.MONDAY, "09:00", "10:15", 30, true));

        assertThat(startTimes(agenda.freeSlots(MONDAY, DAY_BEFORE, List.of()))).containsExactly("09:00", "09:30");
    }

    @Test
    void disabledSchedulesAndOtherDaysGenerateNothing() {
        ProfessionalAgenda agenda = agenda(schedule(DayOfWeek.MONDAY, "09:00", "12:00", 30, false),
                schedule(DayOfWeek.TUESDAY, "09:00", "12:00", 30, true));

        assertThat(agenda.freeSlots(MONDAY, DAY_BEFORE, List.of())).isEmpty();
        assertThat(agenda.worksOn(MONDAY)).isFalse();
        assertThat(agenda.worksOn(MONDAY.plusDays(1))).isTrue();
    }

    @Test
    void aRepeatedTimeAppearsOnceWithTheDurationOfTheEarlierSchedule() {
        ProfessionalAgenda agenda = agenda(schedule(DayOfWeek.MONDAY, "11:00", "13:00", 60, true),
                schedule(DayOfWeek.MONDAY, "09:00", "12:00", 30, true));

        assertThat(agenda.freeSlots(MONDAY, DAY_BEFORE, List.of()))
                .extracting(slot -> slot.getStartTime().toString(), FreeSlot::getDurationMinutes)
                .containsExactly(tuple("09:00", 30), tuple("09:30", 30), tuple("10:00", 30), tuple("10:30", 30),
                        tuple("11:00", 30), tuple("11:30", 30), tuple("12:00", 60));
    }

    @Test
    void todayTheSlotsThatAlreadyStartedAreDiscarded() {
        ProfessionalAgenda agenda = agenda(schedule(DayOfWeek.MONDAY, "09:00", "12:00", 30, true));

        assertThat(startTimes(agenda.freeSlots(MONDAY, MONDAY.atTime(10, 10), List.of())))
                .containsExactly("10:30", "11:00", "11:30");
        assertThat(startTimes(agenda.freeSlots(MONDAY, MONDAY.atTime(10, 30), List.of())))
                .containsExactly("11:00", "11:30");
    }

    @Test
    void anotherDayKeepsTheSlotsEvenIfTheirTimeAlreadyPassedToday() {
        ProfessionalAgenda agenda = agenda(schedule(DayOfWeek.MONDAY, "09:00", "10:00", 30, true));

        assertThat(startTimes(agenda.freeSlots(MONDAY, MONDAY.minusDays(1).atTime(23, 0), List.of())))
                .containsExactly("09:00", "09:30");
    }

    @Test
    void occupiedSlotsAreDiscarded() {
        ProfessionalAgenda agenda = agenda(schedule(DayOfWeek.MONDAY, "09:00", "11:00", 30, true));

        assertThat(startTimes(agenda.freeSlots(MONDAY, DAY_BEFORE, List.of(LocalTime.of(9, 0), LocalTime.of(9, 30)))))
                .containsExactly("10:00", "10:30");
    }

    @Test
    void aScheduleNearMidnightNeverWrapsToTheNextDay() {
        ProfessionalAgenda agenda = agenda(schedule(DayOfWeek.MONDAY, "23:00", "23:59", 60, true),
                schedule(DayOfWeek.MONDAY, "22:00", "23:30", 0, true));

        assertThat(agenda.freeSlots(MONDAY, DAY_BEFORE, List.of())).isEmpty();
    }

    private static List<String> startTimes(List<FreeSlot> slots) {
        return slots.stream().map(slot -> slot.getStartTime().toString()).toList();
    }

    private static ProfessionalAgenda agenda(AgendaSchedule... schedules) {
        return new ProfessionalAgenda(true, List.of(schedules));
    }

    private static AgendaSchedule schedule(DayOfWeek day, String start, String end, int duration, boolean enabled) {
        return new AgendaSchedule(day, LocalTime.parse(start), LocalTime.parse(end), duration, enabled);
    }
}
