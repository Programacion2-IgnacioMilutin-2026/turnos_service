package turnos.catedra.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

// Un elemento del listado de reservas. El nombre y el teléfono del paciente no se leen: turnos no los usa
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppointmentResponse {
    private Long reservationId;
    private String reservationProcessId;
    private String externalPatientId;
    private LocalDate appointmentDate;
    private LocalTime startTime;
    private Long professionalId;
    private String status;
    private Instant confirmedAt;
    private Instant cancelledAt;
    private String cancellationReason;
}
