package turnos.reservation.infrastructure.web.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
                .requestEventId("request-event-1").phoneNumber("+5492615551234").reservationId(8251L).confirmedAt(Instant.parse("2026-10-27T04:36:01Z"))
                .cancelledAt(Instant.parse("2026-10-28T15:10:00Z")).cancellationReason("Cambio solicitado por el usuario")
                .rejectionReason("INVALID_PHONE_NUMBER").build();
        assertThat(process).hasNoNullFieldsOrProperties();

        ReservationProcessResponse response = mapper.toResponse(process);

        assertThat(response).usingRecursiveComparison()
                .ignoringFields("status", "startTime", "expiresAt")
                .isEqualTo(process);
        assertThat(response.getStatus()).isEqualTo("HELD");
        assertThat(response.getStartTime()).isEqualTo("10:30");
        assertThat(response.getExpiresAt()).isEqualTo(Instant.parse("2026-10-27T04:44:38Z"));
        assertThat(response).hasNoNullFieldsOrProperties();
    }

    @Test
    void aRejectedPhoneShowsItsReasonAndTheProcessStaysOpen() {
        ReservationProcessResponse response = mapper.toResponse(baseProcess(ReservationStatus.WAITING_FOR_PHONE)
                .expiresAt(Instant.parse("2026-10-27T04:44:38Z")).rejectionReason("INVALID_PHONE_NUMBER").build());

        assertThat(response.getRejectionReason()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(response.getExpiresAt()).isEqualTo(Instant.parse("2026-10-27T04:44:38Z"));
        assertThat(response.getReservationId()).isNull();
        assertThat(response.getConfirmedAt()).isNull();
        assertThat(response.getCancelledAt()).isNull();
        assertThat(response.getCancellationReason()).isNull();
    }

    @Test
    void aConfirmedReservationShowsItsIdAndWhenItWasConfirmedInSeconds() {
        ReservationProcessResponse response = mapper.toResponse(baseProcess(ReservationStatus.CONFIRMED)
                .expiresAt(Instant.parse("2026-10-27T04:44:38Z")).reservationId(8251L)
                .confirmedAt(Instant.parse("2026-10-27T04:36:01.987654Z")).build());

        assertThat(response.getReservationId()).isEqualTo(8251L);
        assertThat(response.getConfirmedAt()).isEqualTo(Instant.parse("2026-10-27T04:36:01Z"));
        assertThat(response.getExpiresAt()).isNull();
        assertThat(response.getRejectionReason()).isNull();
        assertThat(response.getCancelledAt()).isNull();
        assertThat(response.getCancellationReason()).isNull();
    }

    @Test
    void aReservationCancelledAfterConfirmationKeepsItsIdAndShowsTheCancellationInSeconds() {
        ReservationProcessResponse response = mapper.toResponse(baseProcess(ReservationStatus.CANCELLED)
                .reservationId(8251L).confirmedAt(Instant.parse("2026-10-27T04:36:01Z"))
                .cancelledAt(Instant.parse("2026-10-28T15:10:00.123456Z"))
                .cancellationReason("Cambio solicitado por el usuario").build());

        assertThat(response.getReservationId()).isEqualTo(8251L);
        assertThat(response.getConfirmedAt()).isEqualTo(Instant.parse("2026-10-27T04:36:01Z"));
        assertThat(response.getCancelledAt()).isEqualTo(Instant.parse("2026-10-28T15:10:00Z"));
        assertThat(response.getCancellationReason()).isEqualTo("Cambio solicitado por el usuario");
        assertThat(response.getExpiresAt()).isNull();
    }

    @Test
    void aProcessCancelledBeforeConfirmationHasNoReservationAndItsReasonMayBeMissing() {
        ReservationProcessResponse response = mapper.toResponse(baseProcess(ReservationStatus.CANCELLED)
                .cancelledAt(Instant.parse("2026-10-28T15:10:00Z")).build());

        assertThat(response.getCancelledAt()).isEqualTo(Instant.parse("2026-10-28T15:10:00Z"));
        assertThat(response.getReservationId()).isNull();
        assertThat(response.getConfirmedAt()).isNull();
        assertThat(response.getCancellationReason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"EXPIRED", "INVALID"})
    void aProcessClosedWithoutAReservationHasNothingElseToShow(ReservationStatus status) {
        ReservationProcessResponse response = mapper.toResponse(baseProcess(status)
                .expiresAt(Instant.parse("2026-10-27T04:44:38Z")).build());

        assertThat(response.getExpiresAt()).isNull();
        assertThat(response.getRejectionReason()).isNull();
        assertThat(response.getReservationId()).isNull();
        assertThat(response.getConfirmedAt()).isNull();
        assertThat(response.getCancelledAt()).isNull();
        assertThat(response.getCancellationReason()).isNull();
    }

    @Test
    void theResponseNeverHasTheHoldTheReconciliationMarkThePhoneRequestOrThePhone() {
        assertThat(Arrays.stream(ReservationProcessResponse.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("holdId", "needsReconciliation", "requestEventId", "phoneNumber");
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CANCELLED", "EXPIRED", "INVALID"})
    void aClosedProcessHasNoExpirationAndShowsItsStatus(ReservationStatus status) {
        ReservationProcess closed = ReservationProcess.builder().reservationProcessId("process-1").professionalId(201L)
                .date(LocalDate.of(2026, 11, 2)).startTime(LocalTime.of(10, 30)).status(status)
                .expiresAt(Instant.parse("2026-10-27T04:44:38Z")).build();

        ReservationProcessResponse response = mapper.toResponse(closed);

        assertThat(response.getExpiresAt()).isNull();
        assertThat(response.getStatus()).isEqualTo(status.name());
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

    private static ReservationProcess.ReservationProcessBuilder baseProcess(ReservationStatus status) {
        return ReservationProcess.builder().reservationProcessId("process-1").professionalId(201L)
                .date(LocalDate.of(2026, 11, 2)).startTime(LocalTime.of(10, 30)).status(status);
    }
}
