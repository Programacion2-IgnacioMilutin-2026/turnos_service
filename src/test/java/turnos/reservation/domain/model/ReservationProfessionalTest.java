package turnos.reservation.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationProfessionalTest {

    // Un lunes
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    @ParameterizedTest
    @ValueSource(strings = {"09:00", "10:30", "11:30"})
    void theSlotsOfAnEnabledScheduleAreOffered(String startTime) {
        assertThat(professional(schedule(DayOfWeek.MONDAY, "09:00", "12:00", 30, true))
                .offersSlot(MONDAY, LocalTime.parse(startTime))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10:15", "12:00", "08:30", "09:30:15", "11:45"})
    void aTimeThatNoScheduleGeneratesIsNotOffered(String startTime) {
        assertThat(professional(schedule(DayOfWeek.MONDAY, "09:00", "12:00", 30, true))
                .offersSlot(MONDAY, LocalTime.parse(startTime))).isFalse();
    }

    @Test
    void aSlotThatWouldEndAfterTheCloseIsNotOffered() {
        ReservationProfessional professional = professional(schedule(DayOfWeek.MONDAY, "09:00", "10:15", 30, true));

        assertThat(professional.offersSlot(MONDAY, LocalTime.of(9, 30))).isTrue();
        assertThat(professional.offersSlot(MONDAY, LocalTime.of(10, 0))).isFalse();
    }

    @Test
    void disabledSchedulesAndSchedulesOfOtherDaysOfferNothing() {
        assertThat(professional(schedule(DayOfWeek.MONDAY, "09:00", "12:00", 30, false))
                .offersSlot(MONDAY, LocalTime.of(9, 0))).isFalse();
        assertThat(professional(schedule(DayOfWeek.TUESDAY, "09:00", "12:00", 30, true))
                .offersSlot(MONDAY, LocalTime.of(9, 0))).isFalse();
    }

    @Test
    void withTwoSchedulesThatDayItIsEnoughThatOneGeneratesTheSlot() {
        ReservationProfessional professional = professional(
                schedule(DayOfWeek.MONDAY, "09:00", "12:00", 30, true),
                schedule(DayOfWeek.MONDAY, "11:00", "13:00", 60, true));

        assertThat(professional.offersSlot(MONDAY, LocalTime.of(11, 30))).isTrue();
        assertThat(professional.offersSlot(MONDAY, LocalTime.of(12, 0))).isTrue();
        assertThat(professional.offersSlot(MONDAY, LocalTime.of(12, 30))).isFalse();
    }

    @Test
    void aScheduleNeverGoesPastMidnightNorLoopsWithoutDuration() {
        assertThat(professional(schedule(DayOfWeek.MONDAY, "23:00", "23:59", 60, true))
                .offersSlot(MONDAY, LocalTime.of(23, 0))).isFalse();
        assertThat(professional(schedule(DayOfWeek.MONDAY, "09:00", "12:00", 0, true))
                .offersSlot(MONDAY, LocalTime.of(9, 0))).isFalse();
    }

    private static ReservationProfessional professional(ReservationSchedule... schedules) {
        return ReservationProfessional.builder().enabled(true).firstName("Ana").lastName("Gomez")
                .categoryName("Clinica medica").schedules(List.of(schedules)).build();
    }

    private static ReservationSchedule schedule(DayOfWeek day, String start, String end, int duration, boolean enabled) {
        return ReservationSchedule.builder().dayOfWeek(day).startTime(LocalTime.parse(start)).endTime(LocalTime.parse(end))
                .slotDurationMinutes(duration).enabled(enabled).build();
    }
}
