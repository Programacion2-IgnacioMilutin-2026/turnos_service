package turnos.reservation.infrastructure.web.mapper;

import org.junit.jupiter.api.Test;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.infrastructure.web.dto.ReservationProcessRequest;
import turnos.reservation.infrastructure.web.dto.ReservationProcessResponse;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationProcessDtoMapperTest {

    private final ReservationProcessDtoMapper mapper = new ReservationProcessDtoMapper();

    @Test
    void everyExposedFieldOfAnOpenProcessReachesTheResponse() {
        ReservationProcess process = ReservationProcess.builder().reservationProcessId("process-1").holdId("hold-1")
                .userId(42L).professionalId(201L).professionalFirstName("Ana").professionalLastName("Gomez")
                .professionalCategoryName("Clinica medica").date(LocalDate.of(2026, 11, 2)).startTime(LocalTime.of(10, 30))
                .status(ReservationStatus.HELD).expiresAt(Instant.parse("2026-10-27T04:44:38.627744064Z"))
                .requestEventId("request-event-1").build();
        assertThat(process).hasNoNullFieldsOrProperties();

        ReservationProcessResponse response = mapper.toResponse(process);

        assertThat(response).usingRecursiveComparison()
                .ignoringFields("status", "startTime", "expiresAt", "rejectionReason", "reservationId", "confirmedAt",
                        "cancelledAt", "cancellationReason")
                .isEqualTo(process);
        assertThat(response.getStatus()).isEqualTo("HELD");
        assertThat(response.getStartTime()).isEqualTo("10:30");
        assertThat(response.getExpiresAt()).isEqualTo(Instant.parse("2026-10-27T04:44:38Z"));
        assertThat(response).hasAllNullFieldsOrPropertiesExcept("reservationProcessId", "status", "professionalId",
                "professionalFirstName", "professionalLastName", "professionalCategoryName", "date", "startTime", "expiresAt");
    }

    @Test
    void theResponseNeverHasTheHoldTheReconciliationMarkOrThePhoneRequest() {
        assertThat(Arrays.stream(ReservationProcessResponse.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("holdId", "needsReconciliation", "requestEventId");
    }

    @Test
    void aClosedProcessHasNoExpiration() {
        ReservationProcess expired = ReservationProcess.builder().reservationProcessId("process-1").professionalId(201L)
                .date(LocalDate.of(2026, 11, 2)).startTime(LocalTime.of(10, 30)).status(ReservationStatus.EXPIRED)
                .expiresAt(Instant.parse("2026-10-27T04:44:38Z")).build();

        assertThat(mapper.toResponse(expired).getExpiresAt()).isNull();
    }

    @Test
    void theProfessionalIdBecomesAWholeNumber() {
        ReservationProcessRequest request = new ReservationProcessRequest();
        request.setProfessionalId(new BigDecimal("201.000"));

        assertThat(mapper.toProfessionalId(request)).isEqualTo(201L);
    }

    @Test
    void nullMapsToNull() {
        assertThat(mapper.toResponse(null)).isNull();
        assertThat(mapper.toProfessionalId(null)).isNull();
    }
}
