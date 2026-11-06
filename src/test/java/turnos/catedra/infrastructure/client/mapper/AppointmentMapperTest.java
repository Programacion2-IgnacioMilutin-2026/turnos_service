package turnos.catedra.infrastructure.client.mapper;

import org.junit.jupiter.api.Test;
import turnos.catedra.domain.model.Appointment;
import turnos.catedra.domain.model.AppointmentStatus;
import turnos.catedra.infrastructure.client.dto.AppointmentResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppointmentMapperTest {

    private final AppointmentMapper mapper = new AppointmentMapper();

    @Test
    void everyFieldReachesTheDomain() {
        AppointmentResponse response = AppointmentResponse.builder()
                .reservationId(8251L)
                .reservationProcessId("process-1")
                .externalPatientId("1042")
                .appointmentDate(LocalDate.of(2026, 10, 20))
                .startTime(LocalTime.of(9, 30))
                .professionalId(15L)
                .status("CANCELLED")
                .confirmedAt(Instant.parse("2026-10-12T14:36:00Z"))
                .cancelledAt(Instant.parse("2026-10-13T09:00:00Z"))
                .cancellationReason("Paciente no asiste")
                .build();
        assertThat(response).hasNoNullFieldsOrProperties();

        Appointment appointment = mapper.toDomain(List.of(response)).getFirst();

        assertThat(appointment).isEqualTo(new Appointment("process-1", "1042", AppointmentStatus.CANCELLED, 8251L,
                Instant.parse("2026-10-12T14:36:00Z"), Instant.parse("2026-10-13T09:00:00Z"), "Paciente no asiste", 15L,
                LocalDate.of(2026, 10, 20), LocalTime.of(9, 30)));
    }

    @Test
    void anUnknownOrMissingStatusIsKeptAsUnknown() {
        AppointmentResponse unknown = AppointmentResponse.builder().reservationProcessId("process-1").status("ON_HOLD").build();
        AppointmentResponse missing = AppointmentResponse.builder().reservationProcessId("process-2").build();

        assertThat(mapper.toDomain(List.of(unknown, missing))).extracting(Appointment::status)
                .containsExactly(AppointmentStatus.UNKNOWN, AppointmentStatus.UNKNOWN);
    }

    @Test
    void anEmptyElementOfTheListIsSkipped() {
        AppointmentResponse failed = AppointmentResponse.builder().reservationProcessId("process-1").status("FAILED").build();

        assertThat(mapper.toDomain(Arrays.asList(null, failed))).extracting(Appointment::reservationProcessId)
                .containsExactly("process-1");
    }
}
