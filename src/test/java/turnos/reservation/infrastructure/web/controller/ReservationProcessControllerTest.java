package turnos.reservation.infrastructure.web.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import turnos.TestSecurityConfiguration;
import turnos.TestTokens;
import turnos.reservation.application.exception.ReservationCatalogUnavailableException;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationHoldExpiredException;
import turnos.reservation.application.exception.ReservationHoldNotFoundException;
import turnos.reservation.application.exception.ReservationHoldNotSavedException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationInvalidProcessStateException;
import turnos.reservation.application.exception.ReservationInvalidSlotException;
import turnos.reservation.application.exception.ReservationLocalStateOutOfSyncException;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.exception.ReservationOpenProcessExistsException;
import turnos.reservation.application.exception.ReservationPatientMismatchException;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.application.exception.ReservationProfessionalDisabledException;
import turnos.reservation.application.exception.ReservationProfessionalNotFoundException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.application.exception.ReservationSlotInPastException;
import turnos.reservation.application.exception.ReservationSlotNotAvailableException;
import turnos.reservation.application.exception.ReservationUserNotFoundException;
import turnos.reservation.application.service.ReservationService;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.infrastructure.web.mapper.ReservationProcessDtoMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationProcessController.class)
@Import({TestSecurityConfiguration.class, ReservationProcessDtoMapper.class})
class ReservationProcessControllerTest {

    private static final String PATH = "/api/reservation-processes";
    private static final String TOKEN = TestTokens.forUser("42", "ROLE_USER");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final LocalTime TEN_THIRTY = LocalTime.of(10, 30);
    private static final String VALID = """
            {"professionalId":201,"date":"2026-11-02","startTime":"10:30"}
            """;
    private static final ReservationProcess HELD = ReservationProcess.builder()
            .reservationProcessId("process-1").holdId("hold-1").userId(42L).professionalId(201L)
            .professionalFirstName("Ana").professionalLastName("Gomez").professionalCategoryName("Clinica medica")
            .date(DATE).startTime(TEN_THIRTY).status(ReservationStatus.HELD)
            .expiresAt(Instant.parse("2026-10-27T04:44:38.627744064Z")).build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @Test
    void aHeldProcessIsCreatedWithTheFieldsOfTheAnswerAndWithoutTheHoldId() throws Exception {
        when(reservationService.createReservationProcess(42L, 201L, DATE, TEN_THIRTY, TOKEN)).thenReturn(HELD);

        MockHttpServletResponse response = request(VALID)
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();

        Map<String, Object> body = JsonPath.read(response.getContentAsString(), "$");
        assertThat(body).containsOnlyKeys("reservationProcessId", "status", "professionalId", "professionalFirstName",
                "professionalLastName", "professionalCategoryName", "date", "startTime", "expiresAt", "rejectionReason",
                "reservationId", "confirmedAt", "cancelledAt", "cancellationReason");
        assertThat(body).containsEntry("reservationProcessId", "process-1").containsEntry("status", "HELD")
                .containsEntry("professionalId", 201).containsEntry("professionalFirstName", "Ana")
                .containsEntry("professionalLastName", "Gomez").containsEntry("professionalCategoryName", "Clinica medica")
                .containsEntry("date", "2026-11-02").containsEntry("startTime", "10:30")
                .containsEntry("expiresAt", "2026-10-27T04:44:38Z")
                .containsEntry("rejectionReason", null).containsEntry("reservationId", null)
                .containsEntry("confirmedAt", null).containsEntry("cancelledAt", null).containsEntry("cancellationReason", null);
        assertThat(response.getContentAsString()).doesNotContain("hold-1");
    }

    @Test
    void theTimeIsAcceptedWithAndWithoutSeconds() throws Exception {
        when(reservationService.createReservationProcess(42L, 201L, DATE, TEN_THIRTY, TOKEN)).thenReturn(HELD);

        request("{\"professionalId\":201,\"date\":\"2026-11-02\",\"startTime\":\"10:30:00\"}").andExpect(status().isCreated());
    }

    @ParameterizedTest
    @CsvSource({"1.0,1", "15.000,15", "\"15\",15", "1e3,1000"})
    void aWholeNumberWrittenWithDecimalsOrAsTextIsAccepted(String professionalId, long expected) throws Exception {
        when(reservationService.createReservationProcess(eq(42L), eq(expected), any(), any(), anyString())).thenReturn(HELD);

        request("{\"professionalId\":" + professionalId + ",\"date\":\"2026-11-02\",\"startTime\":\"10:30\"}")
                .andExpect(status().isCreated());
        verify(reservationService).createReservationProcess(42L, expected, DATE, TEN_THIRTY, TOKEN);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"date\":\"2026-11-02\",\"startTime\":\"10:30\"}|professionalId|Professional id is mandatory",
            "{\"professionalId\":201,\"startTime\":\"10:30\"}|date|Date is mandatory",
            "{\"professionalId\":201,\"date\":\"2026-11-02\"}|startTime|Start time is mandatory",
            "{\"professionalId\":0,\"date\":\"2026-11-02\",\"startTime\":\"10:30\"}|professionalId|Professional id must be a positive number",
            "{\"professionalId\":-1,\"date\":\"2026-11-02\",\"startTime\":\"10:30\"}|professionalId|Professional id must be a positive number",
            "{\"professionalId\":1.5,\"date\":\"2026-11-02\",\"startTime\":\"10:30\"}|professionalId|Professional id must be a whole number",
            "{\"professionalId\":1.50,\"date\":\"2026-11-02\",\"startTime\":\"10:30\"}|professionalId|Professional id must be a whole number",
            "{\"professionalId\":99999999999999999999,\"date\":\"2026-11-02\",\"startTime\":\"10:30\"}|professionalId|Professional id is too large",
            "{\"professionalId\":\"abc\",\"date\":\"2026-11-02\",\"startTime\":\"10:30\"}|professionalId|invalid value",
            "{\"professionalId\":201,\"date\":\"2026-02-30\",\"startTime\":\"10:30\"}|date|invalid value",
            "{\"professionalId\":201,\"date\":\"02-11-2026\",\"startTime\":\"10:30\"}|date|invalid value",
            "{\"professionalId\":201,\"date\":\"2026-11-02\",\"startTime\":\"25:00\"}|startTime|invalid value"})
    void anInvalidFieldAnswersValidationErrorWithThatFieldOnly(String body, String field, String message) throws Exception {
        expectProblem(request(body), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(message));
        verifyNoInteractions(reservationService);
    }

    @Test
    void malformedJsonAnswersValidationErrorWithoutFieldErrors() throws Exception {
        expectProblem(request("{\"professionalId\":201,"), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
        verifyNoInteractions(reservationService);
    }

    @Test
    void validationMessagesAreInEnglishWhateverLanguageTheClientAsksFor() throws Exception {
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "es-AR").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='date')].message").value("Date is mandatory"));
    }

    @ParameterizedTest
    @CsvSource({"date,must not be before today", "startTime,must be after the current time"})
    void aSlotInThePastAnswersValidationErrorOnItsField(String field, String message) throws Exception {
        throwing(new ReservationSlotInPastException(field));

        expectProblem(request(VALID), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(message));
    }

    @Test
    void eachErrorAnswersItsStatusAndCodeWithoutTheInternalReason() throws Exception {
        Map<RuntimeException, List<Object>> expected = Map.of(
                new ReservationProfessionalNotFoundException(201L), List.of(404, "PROFESSIONAL_NOT_FOUND"),
                new ReservationProfessionalDisabledException(201L), List.of(409, "PROFESSIONAL_DISABLED"),
                new ReservationInvalidSlotException(201L), List.of(400, "INVALID_SLOT"),
                new ReservationSlotNotAvailableException(201L), List.of(409, "SLOT_NOT_AVAILABLE"),
                new ReservationCatalogUnavailableException("catalog-internal-reason"), List.of(503, "CATALOG_UNAVAILABLE"),
                new ReservationServiceDegradedException("config-internal-reason"), List.of(503, "SERVICE_DEGRADED"),
                new ReservationCentralUnavailableException("catedra-internal-reason"), List.of(503, "CENTRAL_UNAVAILABLE"),
                new ReservationIntegrationException("integration-internal-reason"), List.of(500, "INTERNAL_ERROR"),
                new ReservationNotSavedException("process-1", new IllegalStateException("db")), List.of(500, "INTERNAL_ERROR"));

        for (Map.Entry<RuntimeException, List<Object>> entry : expected.entrySet()) {
            throwing(entry.getKey());
            String body = expectProblem(request(VALID), (Integer) entry.getValue().get(0), (String) entry.getValue().get(1))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("internal-reason");
        }
    }

    @Test
    void anOpenProcessAnswersConflictWithItsData() throws Exception {
        throwing(new ReservationOpenProcessExistsException(ReservationProcess.builder().reservationProcessId("process-open")
                .status(ReservationStatus.WAITING_FOR_PHONE).expiresAt(Instant.parse("2026-10-27T04:44:38.9Z")).build()));

        expectProblem(request(VALID), 409, "OPEN_PROCESS_EXISTS")
                .andExpect(jsonPath("$.reservationProcessId").value("process-open"))
                .andExpect(jsonPath("$.currentStatus").value("WAITING_FOR_PHONE"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-27T04:44:38Z"));
    }

    @Test
    void aHoldThatCouldNotBeSavedTellsUntilWhenTheSlotStaysHeld() throws Exception {
        throwing(new ReservationHoldNotSavedException("process-1", Instant.parse("2026-10-27T04:44:38.6Z"), 540,
                new IllegalStateException("db")));

        expectProblem(request(VALID), 500, "HOLD_NOT_SAVED")
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "540"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-27T04:44:38Z"));
    }

    @Test
    void aUserThatNoLongerExistsAnswersLikeAnInvalidToken() throws Exception {
        throwing(new ReservationUserNotFoundException(42L));

        MockHttpServletResponse missingUser = request(VALID).andReturn().getResponse();
        MockHttpServletResponse alteredToken = mockMvc.perform(post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withPayload(TOKEN, "{\"sub\":\"7\"}"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andReturn().getResponse();

        assertThat(missingUser.getStatus()).isEqualTo(401);
        assertThat(missingUser.getContentType()).isEqualTo(alteredToken.getContentType());
        assertThat(missingUser.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(alteredToken.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertThat((Map<String, Object>) JsonPath.read(missingUser.getContentAsString(), "$"))
                .isEqualTo(JsonPath.read(alteredToken.getContentAsString(), "$"));
    }

    @Test
    void withoutTokenTheServiceIsNeverCalled() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void theOwnerComesFromTheTokenAndNeverFromTheBody() throws Exception {
        when(reservationService.createReservationProcess(anyLong(), anyLong(), any(), any(), anyString())).thenReturn(HELD);

        request("{\"professionalId\":201,\"date\":\"2026-11-02\",\"startTime\":\"10:30\",\"userId\":99}")
                .andExpect(status().isCreated());
        verify(reservationService).createReservationProcess(42L, 201L, DATE, TEN_THIRTY, TOKEN);
    }

    // Marcado para reconciliar a propósito: la marca nunca aparece en la respuesta
    private static final ReservationProcess WAITING = ReservationProcess.builder()
            .reservationProcessId("process-1").holdId("hold-1").userId(42L).professionalId(201L)
            .professionalFirstName("Ana").professionalLastName("Gomez").professionalCategoryName("Clinica medica")
            .date(DATE).startTime(TEN_THIRTY).status(ReservationStatus.WAITING_FOR_PHONE)
            .expiresAt(Instant.parse("2026-10-27T04:44:38.627744064Z")).needsReconciliation(true).build();

    @Test
    void aConfirmedProcessAnswersOkWithTheFieldsOfTheAnswerWithoutTheHoldNorTheMark() throws Exception {
        when(reservationService.confirmReservationProcess(42L, "process-1")).thenReturn(WAITING);

        MockHttpServletResponse response = confirm("process-1")
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();

        Map<String, Object> body = JsonPath.read(response.getContentAsString(), "$");
        assertThat(body).containsOnlyKeys("reservationProcessId", "status", "professionalId", "professionalFirstName",
                "professionalLastName", "professionalCategoryName", "date", "startTime", "expiresAt", "rejectionReason",
                "reservationId", "confirmedAt", "cancelledAt", "cancellationReason");
        assertThat(body).containsEntry("reservationProcessId", "process-1").containsEntry("status", "WAITING_FOR_PHONE")
                .containsEntry("startTime", "10:30").containsEntry("expiresAt", "2026-10-27T04:44:38Z")
                .containsEntry("rejectionReason", null).containsEntry("reservationId", null)
                .containsEntry("confirmedAt", null).containsEntry("cancelledAt", null).containsEntry("cancellationReason", null);
        assertThat(response.getContentAsString()).doesNotContain("hold-1").doesNotContain("econciliation");
    }

    @Test
    void eachConfirmationErrorAnswersItsStatusAndCodeWithoutTheInternalReason() throws Exception {
        Map<RuntimeException, List<Object>> expected = Map.ofEntries(
                Map.entry(new ReservationProcessNotFoundException("process-1"), List.of(404, "RESERVATION_PROCESS_NOT_FOUND")),
                Map.entry(new ReservationHoldExpiredException("hold-1"), List.of(409, "HOLD_EXPIRED")),
                Map.entry(new ReservationHoldNotFoundException("hold-1"), List.of(404, "HOLD_NOT_FOUND")),
                Map.entry(new ReservationPatientMismatchException("hold-1"), List.of(409, "PATIENT_MISMATCH")),
                Map.entry(new ReservationProfessionalNotFoundException(201L), List.of(404, "PROFESSIONAL_NOT_FOUND")),
                Map.entry(new ReservationInvalidSlotException(201L), List.of(400, "INVALID_SLOT")),
                Map.entry(new ReservationSlotNotAvailableException(201L), List.of(409, "SLOT_NOT_AVAILABLE")),
                Map.entry(new ReservationServiceDegradedException("config-internal-reason"), List.of(503, "SERVICE_DEGRADED")),
                Map.entry(new ReservationCentralUnavailableException("catedra-internal-reason"), List.of(503, "CENTRAL_UNAVAILABLE")),
                Map.entry(new ReservationLocalStateOutOfSyncException("process-1", new IllegalStateException("db-internal-reason")),
                        List.of(500, "LOCAL_STATE_OUT_OF_SYNC")),
                Map.entry(new ReservationIntegrationException("Hold owner mismatch"), List.of(500, "INTERNAL_ERROR")));

        for (Map.Entry<RuntimeException, List<Object>> entry : expected.entrySet()) {
            doThrow(entry.getKey()).when(reservationService).confirmReservationProcess(anyLong(), anyString());
            String body = expectProblem(confirm("process-1"), (Integer) entry.getValue().get(0), (String) entry.getValue().get(1))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("internal-reason").doesNotContain("hold-1").doesNotContain("mismatch");
        }
    }

    @Test
    void aProcessInAnotherStateAnswersConflictWithItsCurrentStatus() throws Exception {
        doThrow(new ReservationInvalidProcessStateException("process-1", ReservationStatus.PHONE_SUBMITTED))
                .when(reservationService).confirmReservationProcess(anyLong(), anyString());

        expectProblem(confirm("process-1"), 409, "INVALID_PROCESS_STATE")
                .andExpect(jsonPath("$.currentStatus").value("PHONE_SUBMITTED"));
    }

    @Test
    void withoutTokenTheConfirmationIsNeverRequested() throws Exception {
        mockMvc.perform(post(PATH + "/process-1/confirm")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void theOwnerComesFromTheTokenAndTheProcessFromThePathIgnoringAnyBody() throws Exception {
        when(reservationService.confirmReservationProcess(anyLong(), anyString())).thenReturn(WAITING);

        mockMvc.perform(post(PATH + "/process-1/confirm").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":99,\"reservationProcessId\":\"process-9\"}"))
                .andExpect(status().isOk());
        verify(reservationService).confirmReservationProcess(42L, "process-1");
    }

    private ResultActions confirm(String reservationProcessId) throws Exception {
        return mockMvc.perform(post(PATH + "/" + reservationProcessId + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));
    }

    private void throwing(RuntimeException exception) {
        doThrow(exception).when(reservationService).createReservationProcess(anyLong(), anyLong(), any(), any(), anyString());
    }

    private ResultActions request(String body) throws Exception {
        return mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static ResultActions expectProblem(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }
}
