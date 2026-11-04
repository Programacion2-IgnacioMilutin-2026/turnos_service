package turnos.reservation.infrastructure.web.mapper;

import org.springframework.stereotype.Component;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.infrastructure.web.dto.ReservationCancellationRequest;
import turnos.reservation.infrastructure.web.dto.ReservationProcessListRequest;
import turnos.reservation.infrastructure.web.dto.ReservationProcessPageResponse;
import turnos.reservation.infrastructure.web.dto.ReservationProcessRequest;
import turnos.reservation.infrastructure.web.dto.ReservationProcessResponse;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Component
public class ReservationProcessDtoMapper {

    private static final DateTimeFormatter HOUR_AND_MINUTE = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 10;

    // La validación del request ya garantizó un entero positivo dentro del rango de un Long
    public Long toProfessionalId(ReservationProcessRequest request) {
        if (request == null || request.getProfessionalId() == null) {
            return null;
        }
        return request.getProfessionalId().longValueExact();
    }

    // El cuerpo es opcional, y un motivo sin texto es como no tener motivo. El carácter nulo se quita acá porque la base
    // lo rechaza, y así lo que se le envía a la cátedra y lo que se guarda es lo mismo
    public String toReason(ReservationCancellationRequest request) {
        if (request == null || request.getReason() == null) {
            return null;
        }
        String reason = request.getReason().replace("\0", "");
        return reason.isBlank() ? null : reason;
    }

    // La validación del request ya garantizó que, si tiene un valor, es uno de los estados que se pueden pedir
    public ReservationStatus toStatus(ReservationProcessListRequest request) {
        String status = request.getStatus();
        return status == null || status.isEmpty() ? null : ReservationStatus.valueOf(status);
    }

    public int toPage(ReservationProcessListRequest request) {
        return request.getPage() == null ? DEFAULT_PAGE : request.getPage();
    }

    public int toSize(ReservationProcessListRequest request) {
        return request.getSize() == null ? DEFAULT_SIZE : request.getSize();
    }

    public ReservationProcessPageResponse toPageResponse(ReservationProcessPage result, int page, int size) {
        return ReservationProcessPageResponse.builder()
                .items(result.items().stream().map(this::toResponse).toList())
                .page(page)
                .size(size)
                .totalItems(result.totalItems())
                .build();
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
                .expiresAt(domain.isOpen() ? inSeconds(domain.getExpiresAt()) : null)
                .rejectionReason(domain.getRejectionReason())
                .reservationId(domain.getReservationId())
                .confirmedAt(inSeconds(domain.getConfirmedAt()))
                .cancelledAt(inSeconds(domain.getCancelledAt()))
                .cancellationReason(domain.getCancellationReason())
                .build();
    }

    private static Instant inSeconds(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS);
    }
}
