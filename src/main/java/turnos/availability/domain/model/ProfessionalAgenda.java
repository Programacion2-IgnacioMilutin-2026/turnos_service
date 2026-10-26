package turnos.availability.domain.model;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// Lo que el cálculo de turnos necesita de un profesional: si atiende y sus horarios semanales
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfessionalAgenda {
    private boolean enabled;
    private List<AgendaSchedule> schedules;

    public boolean worksOn(LocalDate date) {
        return schedules.stream().anyMatch(schedule -> schedule.appliesTo(date.getDayOfWeek()));
    }

    // Los turnos de esa fecha, ordenados, sin los que ya comenzaron (si la fecha es hoy) ni los ocupados.
    // Si dos horarios generan la misma hora, queda una sola vez con la duración del horario que empieza antes
    public List<FreeSlot> freeSlots(LocalDate date, LocalDateTime now, Collection<LocalTime> occupiedStartTimes) {
        Set<LocalTime> occupied = new HashSet<>(occupiedStartTimes);
        Map<LocalTime, FreeSlot> slots = new TreeMap<>();
        schedules.stream()
                .filter(schedule -> schedule.appliesTo(date.getDayOfWeek()))
                .sorted(Comparator.comparing(AgendaSchedule::getStartTime))
                .forEach(schedule -> slotsOf(schedule).forEach(slot -> slots.putIfAbsent(slot.getStartTime(), slot)));
        return slots.values().stream()
                .filter(slot -> !hasStarted(slot, date, now))
                .filter(slot -> !occupied.contains(slot.getStartTime()))
                .toList();
    }

    // Se cuenta en minutos del día para que un turno nunca pase de la medianoche
    private static List<FreeSlot> slotsOf(AgendaSchedule schedule) {
        List<FreeSlot> slots = new ArrayList<>();
        int duration = schedule.getSlotDurationMinutes();
        if (duration <= 0) {
            return slots;
        }
        int end = minuteOfDay(schedule.getEndTime());
        for (int start = minuteOfDay(schedule.getStartTime()); start + duration <= end; start += duration) {
            slots.add(new FreeSlot(LocalTime.of(start / 60, start % 60), duration));
        }
        return slots;
    }

    // Un turno que empieza justo ahora ya comenzó
    private static boolean hasStarted(FreeSlot slot, LocalDate date, LocalDateTime now) {
        return date.equals(now.toLocalDate()) && !slot.getStartTime().isAfter(now.toLocalTime());
    }

    private static int minuteOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
