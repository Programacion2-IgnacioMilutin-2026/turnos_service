package turnos.availability.infrastructure.web.mapper;

import org.springframework.stereotype.Component;
import turnos.availability.domain.model.FreeSlotsResult;
import turnos.availability.infrastructure.web.dto.FreeSlotResponse;
import turnos.availability.infrastructure.web.dto.FreeSlotsResponse;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Component
public class AvailabilityDtoMapper {

    private static final DateTimeFormatter HOUR_AND_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    // Cada turno repite el profesional y la fecha consultados. El instante se informa en segundos, en UTC
    public FreeSlotsResponse toResponse(Long professionalId, LocalDate date, FreeSlotsResult result) {
        return FreeSlotsResponse.builder()
                .slots(result.getSlots().stream()
                        .map(slot -> FreeSlotResponse.builder()
                                .professionalId(professionalId)
                                .date(date)
                                .startTime(slot.getStartTime().format(HOUR_AND_MINUTE))
                                .durationMinutes(slot.getDurationMinutes())
                                .build())
                        .toList())
                .emptyReason(result.getEmptyReason() == null ? null : result.getEmptyReason().name())
                .stale(result.isStale())
                .dataAsOf(result.getDataAsOf().truncatedTo(ChronoUnit.SECONDS))
                .build();
    }
}
