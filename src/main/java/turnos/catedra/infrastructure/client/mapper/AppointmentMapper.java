package turnos.catedra.infrastructure.client.mapper;

import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.Appointment;
import turnos.catedra.domain.model.AppointmentStatus;
import turnos.catedra.infrastructure.client.dto.AppointmentResponse;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class AppointmentMapper {

    // Un estado ausente o desconocido no rechaza la página: puede ser de otra reserva. Quien usa el registro lo valida
    public List<Appointment> toDomain(List<AppointmentResponse> responses) {
        return responses.stream()
                .filter(Objects::nonNull)
                .map(response -> new Appointment(
                        response.getReservationProcessId(),
                        response.getExternalPatientId(),
                        status(response.getStatus()),
                        response.getReservationId(),
                        response.getConfirmedAt(),
                        response.getCancelledAt(),
                        response.getCancellationReason(),
                        response.getProfessionalId(),
                        response.getAppointmentDate(),
                        response.getStartTime()))
                .collect(Collectors.toList());
    }

    private static AppointmentStatus status(String value) {
        if (value == null) {
            return AppointmentStatus.UNKNOWN;
        }
        try {
            return AppointmentStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return AppointmentStatus.UNKNOWN;
        }
    }
}
