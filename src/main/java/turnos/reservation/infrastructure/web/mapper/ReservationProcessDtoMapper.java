package turnos.reservation.infrastructure.web.mapper;

import org.springframework.stereotype.Component;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.infrastructure.web.dto.ReservationProcessRequest;
import turnos.reservation.infrastructure.web.dto.ReservationProcessResponse;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Component
public class ReservationProcessDtoMapper {

    private static final DateTimeFormatter HOUR_AND_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    // La validación del request ya garantizó un entero positivo dentro del rango de un Long
    public Long toProfessionalId(ReservationProcessRequest request) {
        if (request == null || request.getProfessionalId() == null) {
            return null;
        }
        return request.getProfessionalId().longValueExact();
    }

    // El instante se informa en segundos, en UTC. Lo que todavía no ocurrió en el proceso queda en null
    public ReservationProcessResponse toResponse(ReservationProcess domain) {
        if (domain == null) {
            return null;
        }
        return ReservationProcessResponse.builder()
                .reservationProcessId(domain.getReservationProcessId())
                .status(domain.getStatus().name())
                .professionalId(domain.getProfessionalId())
                .professionalFirstName(domain.getProfessionalFirstName())
                .professionalLastName(domain.getProfessionalLastName())
                .professionalCategoryName(domain.getProfessionalCategoryName())
                .date(domain.getDate())
                .startTime(domain.getStartTime().format(HOUR_AND_MINUTE))
                .expiresAt(domain.isOpen() ? domain.getExpiresAt().truncatedTo(ChronoUnit.SECONDS) : null)
                .build();
    }
}
