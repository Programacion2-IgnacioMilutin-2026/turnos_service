package turnos.reservation.infrastructure.web.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.infrastructure.web.dto.ReservationCancellationRequest;
import turnos.reservation.infrastructure.web.dto.ReservationProcessListRequest;
import turnos.reservation.infrastructure.web.dto.ReservationProcessPageResponse;
import turnos.reservation.infrastructure.web.dto.ReservationProcessRequest;
import turnos.reservation.infrastructure.web.dto.ReservationProcessResponse;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

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

    @Test
    void aMissingOrEmptyStatusIsNoFilter() {
        assertThat(mapper.toStatus(new ReservationProcessListRequest(null, null, null))).isNull();
        assertThat(mapper.toStatus(new ReservationProcessListRequest("", null, null))).isNull();
    }

    @Test
    void aStatusBecomesTheEnum() {
        assertThat(mapper.toStatus(new ReservationProcessListRequest("CONFIRMED", null, null))).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void aMissingPageAndSizeUseTheirDefaults() {
        ReservationProcessListRequest request = new ReservationProcessListRequest(null, null, null);

        assertThat(mapper.toPage(request)).isZero();
        assertThat(mapper.toSize(request)).isEqualTo(10);
    }

    @Test
    void aPageAndSizeAreKept() {
        ReservationProcessListRequest request = new ReservationProcessListRequest(null, 2, 25);

        assertThat(mapper.toPage(request)).isEqualTo(2);
        assertThat(mapper.toSize(request)).isEqualTo(25);
    }

    @Test
    void thePageKeepsTheItemsInTheirOrderAndTheTotal() {
        ReservationProcessPage page = new ReservationProcessPage(List.of(
                baseProcess(ReservationStatus.CONFIRMED).reservationProcessId("process-2").build(),
                baseProcess(ReservationStatus.CANCELLED).reservationProcessId("process-1").build()), 7);

        ReservationProcessPageResponse response = mapper.toPageResponse(page, 2, 25);

        assertThat(response.getItems()).extracting(ReservationProcessResponse::getReservationProcessId)
                .containsExactly("process-2", "process-1");
        assertThat(response.getPage()).isEqualTo(2);
        assertThat(response.getSize()).isEqualTo(25);
        assertThat(response.getTotalItems()).isEqualTo(7);
    }

    @Test
    void aReasonIsKeptAsItCame() {
        assertThat(mapper.toReason(new ReservationCancellationRequest("  Cambio de planes "))).isEqualTo("  Cambio de planes ");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n", "\0", " \0 "})
    void aReasonWithoutTextIsNoReason(String reason) {
        assertThat(mapper.toReason(new ReservationCancellationRequest(reason))).isNull();
    }

    @Test
    void withoutABodyThereIsNoReason() {
        assertThat(mapper.toReason(null)).isNull();
    }

    @Test
    void theNullCharacterIsRemovedBecauseTheDatabaseRejectsIt() {
        assertThat(mapper.toReason(new ReservationCancellationRequest("Cam\0bio"))).isEqualTo("Cambio");
    }

    private static ReservationProcess.ReservationProcessBuilder baseProcess(ReservationStatus status) {
        return ReservationProcess.builder().reservationProcessId("process-1").professionalId(201L)
                .date(LocalDate.of(2026, 11, 2)).startTime(LocalTime.of(10, 30)).status(status);
    }
}
