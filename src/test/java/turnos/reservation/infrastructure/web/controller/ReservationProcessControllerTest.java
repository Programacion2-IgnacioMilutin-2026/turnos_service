package turnos.reservation.infrastructure.web.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import turnos.reservation.application.exception.ReservationAppointmentNotFoundException;
import turnos.reservation.application.exception.ReservationAppointmentOwnerMismatchException;
import turnos.reservation.application.exception.ReservationCatalogUnavailableException;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationHoldExpiredException;
import turnos.reservation.application.exception.ReservationHoldNotFoundException;
import turnos.reservation.application.exception.ReservationHoldNotSavedException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationInvalidPhoneNumberException;
import turnos.reservation.application.exception.ReservationInvalidProcessStateException;
import turnos.reservation.application.exception.ReservationInvalidSlotException;
import turnos.reservation.application.exception.ReservationLocalStateOutOfSyncException;
import turnos.reservation.application.exception.ReservationNotCancellableException;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.exception.ReservationOpenProcessExistsException;
import turnos.reservation.application.exception.ReservationPatientMismatchException;
import turnos.reservation.application.exception.ReservationPhoneRequestNotReadyException;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.application.exception.ReservationProfessionalDisabledException;
import turnos.reservation.application.exception.ReservationProfessionalNotFoundException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.application.exception.ReservationSlotInPastException;
import turnos.reservation.application.exception.ReservationSlotNotAvailableException;
import turnos.reservation.application.exception.ReservationUserNotFoundException;
import turnos.reservation.application.service.ReservationService;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;
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

@ExtendWith(OutputCaptureExtension.class)
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

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"PHONE_SUBMITTED", "CONFIRMED", "CANCELLED", "INVALID"})
    void aProcessInAnotherStateAnswersConflictWithItsCurrentStatus(ReservationStatus status) throws Exception {
        doThrow(new ReservationInvalidProcessStateException("process-1", status))
                .when(reservationService).confirmReservationProcess(anyLong(), anyString());

        expectProblem(confirm("process-1"), 409, "INVALID_PROCESS_STATE")
                .andExpect(jsonPath("$.currentStatus").value(status.name()));
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

    @Test
    void aSubmittedPhoneAnswersTheProcessWithoutThePhoneTheHoldOrTheMark() throws Exception {
        ReservationProcess submitted = submittedProcess();
        when(reservationService.submitPhone(42L, "process-1", "+54 9 261 555-1234")).thenReturn(submitted);

        MockHttpServletResponse response = submitPhone("process-1", "{\"phoneNumber\":\"+54 9 261 555-1234\"}")
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();

        Map<String, Object> body = JsonPath.read(response.getContentAsString(), "$");
        assertThat(body).containsOnlyKeys("reservationProcessId", "status", "professionalId", "professionalFirstName",
                "professionalLastName", "professionalCategoryName", "date", "startTime", "expiresAt", "rejectionReason",
                "reservationId", "confirmedAt", "cancelledAt", "cancellationReason");
        assertThat(body).containsEntry("reservationProcessId", "process-1").containsEntry("status", "PHONE_SUBMITTED")
                .containsEntry("startTime", "10:30").containsEntry("expiresAt", "2026-10-27T04:44:38Z")
                .containsEntry("rejectionReason", null).containsEntry("reservationId", null);
        assertThat(response.getContentAsString()).doesNotContain("5492615551234").doesNotContain("hold-1")
                .doesNotContain("request-event-1").doesNotContain("econciliation");
    }

    @Test
    void eachPhoneErrorAnswersItsStatusAndCodeWithoutTheInternalReason() throws Exception {
        Map<RuntimeException, List<Object>> expected = Map.of(
                new ReservationInvalidPhoneNumberException(), List.of(400, "INVALID_PHONE_NUMBER"),
                new ReservationProcessNotFoundException("process-1"), List.of(404, "RESERVATION_PROCESS_NOT_FOUND"),
                new ReservationHoldExpiredException("hold-1"), List.of(409, "HOLD_EXPIRED"),
                new ReservationPhoneRequestNotReadyException("process-1"), List.of(409, "PHONE_REQUEST_NOT_READY"),
                new ReservationCentralUnavailableException("kafka-internal-reason"), List.of(503, "CENTRAL_UNAVAILABLE"),
                new ReservationLocalStateOutOfSyncException("process-1", new IllegalStateException("db-internal-reason")),
                List.of(500, "LOCAL_STATE_OUT_OF_SYNC"),
                new ReservationNotSavedException("process-1", new IllegalStateException("db-internal-reason")),
                List.of(500, "INTERNAL_ERROR"));

        for (Map.Entry<RuntimeException, List<Object>> entry : expected.entrySet()) {
            doThrow(entry.getKey()).when(reservationService).submitPhone(anyLong(), anyString(), anyString());
            String body = expectProblem(submitPhone("process-1", "{\"phoneNumber\":\"+5492615551234\"}"),
                    (Integer) entry.getValue().get(0), (String) entry.getValue().get(1))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("internal-reason").doesNotContain("hold-1").doesNotContain("5492615551234");
        }
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "WAITING_FOR_PHONE")
    void aPhoneForAProcessInAnotherStateAnswersConflictWithItsCurrentStatus(ReservationStatus status) throws Exception {
        doThrow(new ReservationInvalidProcessStateException("process-1", status))
                .when(reservationService).submitPhone(anyLong(), anyString(), anyString());

        expectProblem(submitPhone("process-1", "{\"phoneNumber\":\"+5492615551234\"}"), 409, "INVALID_PROCESS_STATE")
                .andExpect(jsonPath("$.currentStatus").value(status.name()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"phoneNumber\":null}"})
    void aMissingPhoneIsAValidationErrorThatNamesTheFieldWithoutAskingForTheProcess(String body) throws Exception {
        expectProblem(submitPhone("process-1", body), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("phoneNumber"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Phone number is mandatory"));
        verifyNoInteractions(reservationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"phoneNumber\":[\"+5492615551234\"]}", "{\"phoneNumber\":{\"value\":\"+5492615551234\"}}"})
    void aPhoneThatIsNotAScalarIsAValidationErrorThatDoesNotEchoIt(String body, CapturedOutput output) throws Exception {
        String answer = expectProblem(submitPhone("process-1", body), 400, "VALIDATION_ERROR")
                .andReturn().getResponse().getContentAsString();

        assertThat(answer).doesNotContain("5492615551234");
        assertThat(output.getAll()).doesNotContain("5492615551234");
        verifyNoInteractions(reservationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{\"phoneNumber\":\"+5492615551234\"", "\"+5492615551234\""})
    void aBodyThatIsNotTheExpectedObjectIsAValidationErrorThatDoesNotEchoThePhone(String body, CapturedOutput output) throws Exception {
        String answer = expectProblem(submitPhone("process-1", body), 400, "VALIDATION_ERROR")
                .andReturn().getResponse().getContentAsString();

        assertThat(answer).doesNotContain("5492615551234");
        assertThat(output.getAll()).doesNotContain("5492615551234");
        verifyNoInteractions(reservationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc"})
    void anEmptyOrMalformedPhoneReachesTheUseCaseThatDecidesItsFormat(String phone) throws Exception {
        doThrow(new ReservationInvalidPhoneNumberException()).when(reservationService).submitPhone(42L, "process-1", phone);

        expectProblem(submitPhone("process-1", "{\"phoneNumber\":\"" + phone + "\"}"), 400, "INVALID_PHONE_NUMBER");

        verify(reservationService).submitPhone(42L, "process-1", phone);
    }

    @Test
    void aNumberInsteadOfTextIsTreatedAsThePhoneItWrites() throws Exception {
        when(reservationService.submitPhone(42L, "process-1", "5492615551234")).thenReturn(submittedProcess());

        submitPhone("process-1", "{\"phoneNumber\":5492615551234}").andExpect(status().isOk());

        verify(reservationService).submitPhone(42L, "process-1", "5492615551234");
    }

    @Test
    void withoutTokenThePhoneIsNeverSubmitted() throws Exception {
        mockMvc.perform(post(PATH + "/process-1/phone").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+5492615551234\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void anExpiredTokenNeverSubmitsThePhone() throws Exception {
        Instant now = Instant.now();
        String expired = TestTokens.signed(claims -> claims.subject("42").claim("auth", "ROLE_USER")
                .issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(1)));

        mockMvc.perform(post(PATH + "/process-1/phone").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+5492615551234\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void theOwnerOfThePhoneComesFromTheTokenAndTheProcessFromThePathIgnoringTheRestOfTheBody() throws Exception {
        when(reservationService.submitPhone(anyLong(), anyString(), anyString())).thenReturn(submittedProcess());

        submitPhone("process-1", "{\"phoneNumber\":\"2615551234\",\"userId\":99,\"reservationProcessId\":\"process-9\"}")
                .andExpect(status().isOk());

        verify(reservationService).submitPhone(42L, "process-1", "2615551234");
    }

    @Test
    void aProcessIsReturnedWithItsFieldsAndWithoutTheHoldTheMarkThePhoneRequestOrThePhone() throws Exception {
        when(reservationService.getReservationProcessById(42L, "process-1")).thenReturn(ReservationProcess.builder()
                .reservationProcessId("process-1").holdId("hold-1").userId(42L).professionalId(201L)
                .professionalFirstName("Ana").professionalLastName("Gomez").professionalCategoryName("Clinica medica")
                .date(DATE).startTime(TEN_THIRTY).status(ReservationStatus.CONFIRMED)
                .expiresAt(Instant.parse("2026-10-27T04:44:38Z")).requestEventId("request-event-1")
                .phoneNumber("+5492615551234").needsReconciliation(true).reservationId(8251L)
                .confirmedAt(Instant.parse("2026-10-27T04:36:01.987654Z")).build());

        MockHttpServletResponse response = query("process-1", TOKEN)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();

        Map<String, Object> body = JsonPath.read(response.getContentAsString(), "$");
        assertThat(body).containsOnlyKeys("reservationProcessId", "status", "professionalId", "professionalFirstName",
                "professionalLastName", "professionalCategoryName", "date", "startTime", "expiresAt", "rejectionReason",
                "reservationId", "confirmedAt", "cancelledAt", "cancellationReason");
        assertThat(body).containsEntry("reservationProcessId", "process-1").containsEntry("status", "CONFIRMED")
                .containsEntry("startTime", "10:30").containsEntry("reservationId", 8251)
                .containsEntry("confirmedAt", "2026-10-27T04:36:01Z").containsEntry("expiresAt", null);
        assertThat(response.getContentAsString()).doesNotContain("hold-1").doesNotContain("request-event-1")
                .doesNotContain("5492615551234");
    }

    @Test
    void theOwnerOfTheQueryComesFromTheTokenAndTheProcessFromThePath() throws Exception {
        when(reservationService.getReservationProcessById(7L, "process-9")).thenReturn(HELD);

        query("process-9", TestTokens.forUser("7", "ROLE_ADMIN")).andExpect(status().isOk());

        verify(reservationService).getReservationProcessById(7L, "process-9");
    }

    @Test
    void aProcessThatIsNotFoundAnswersItsCodeWithoutTheId() throws Exception {
        when(reservationService.getReservationProcessById(42L, "process-1"))
                .thenThrow(new ReservationProcessNotFoundException("process-1"));

        String body = expectProblem(query("process-1", TOKEN), 404, "RESERVATION_PROCESS_NOT_FOUND")
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(body, "$.detail")).isEqualTo("Reservation process not found");
    }

    @Test
    void aFailureSavingTheOverdueProcessIsAnInternalErrorWithoutTheReason() throws Exception {
        when(reservationService.getReservationProcessById(42L, "process-1"))
                .thenThrow(new ReservationNotSavedException("process-1", new IllegalStateException("database down")));

        String body = expectProblem(query("process-1", TOKEN), 500, "INTERNAL_ERROR")
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("database down").doesNotContain("could not be saved");
    }

    @Test
    void thePhoneInTheCauseOfAFailedSaveNeverReachesTheLog(CapturedOutput output) throws Exception {
        when(reservationService.getReservationProcessById(42L, "process-1")).thenThrow(new ReservationNotSavedException(
                "process-1", new IllegalStateException("could not write the row with +5492615551234")));

        expectProblem(query("process-1", TOKEN), 500, "INTERNAL_ERROR");

        assertThat(output.getAll()).doesNotContain("5492615551234");
    }

    @Test
    void aFailureReadingTheDatabaseIsAnInternalErrorWithoutTheReason() throws Exception {
        when(reservationService.getReservationProcessById(42L, "process-1"))
                .thenThrow(new IllegalStateException("database down"));

        String body = expectProblem(query("process-1", TOKEN), 500, "INTERNAL_ERROR")
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("database down");
    }

    @Test
    void withoutATokenTheProcessIsNeverQueried() throws Exception {
        mockMvc.perform(get(PATH + "/process-1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void anInvalidTokenNeverQueriesTheProcess() throws Exception {
        query("process-1", "not-a-token").andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void anExpiredTokenNeverQueriesTheProcess() throws Exception {
        Instant now = Instant.now();
        String expired = TestTokens.signed(claims -> claims.subject("42").claim("auth", "ROLE_USER")
                .issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(1)));

        query("process-1", expired).andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void aTokenWithoutAValidRoleNeverQueriesTheProcess() throws Exception {
        query("process-1", TestTokens.forUser("42", "ROLE_OTRO")).andExpect(status().isForbidden());
        verifyNoInteractions(reservationService);
    }

    @Test
    void withoutParametersItListsTheFirstPageOfTheOwnerOfTheToken() throws Exception {
        when(reservationService.findReservationProcessesByUser(42L, null, 0, 10)).thenReturn(new ReservationProcessPage(List.of(), 0));

        MockHttpServletResponse response = list("").andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse();

        Map<String, Object> body = JsonPath.read(response.getContentAsString(), "$");
        assertThat(body).containsOnlyKeys("items", "page", "size", "totalItems");
        assertThat(body).containsEntry("items", List.of()).containsEntry("page", 0).containsEntry("size", 10).containsEntry("totalItems", 0);
    }

    @Test
    void theOwnerOfTheListComesFromTheToken() throws Exception {
        when(reservationService.findReservationProcessesByUser(7L, null, 0, 10)).thenReturn(new ReservationProcessPage(List.of(), 0));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forUser("7", "ROLE_ADMIN")))
                .andExpect(status().isOk());

        verify(reservationService).findReservationProcessesByUser(7L, null, 0, 10);
    }

    @Test
    void emptyParametersCountAsIfTheyWereNotSent() throws Exception {
        when(reservationService.findReservationProcessesByUser(42L, null, 0, 10)).thenReturn(new ReservationProcessPage(List.of(), 0));

        list("?status=&page=&size=").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(10));

        verify(reservationService).findReservationProcessesByUser(42L, null, 0, 10);
    }

    @Test
    void theParametersReachTheServiceAndComeBackInThePage() throws Exception {
        when(reservationService.findReservationProcessesByUser(42L, ReservationStatus.CONFIRMED, 2, 25))
                .thenReturn(new ReservationProcessPage(List.of(), 51));

        list("?status=CONFIRMED&page=2&size=25").andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(25)).andExpect(jsonPath("$.totalItems").value(51));
    }

    @Test
    void eachItemHasTheFieldsOfAProcessAndNoPrivateData() throws Exception {
        when(reservationService.findReservationProcessesByUser(42L, null, 0, 10))
                .thenReturn(new ReservationProcessPage(List.of(submittedProcess()), 1));

        String body = list("").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        Map<String, Object> item = JsonPath.read(body, "$.items[0]");
        assertThat(item).containsOnlyKeys("reservationProcessId", "status", "professionalId", "professionalFirstName",
                "professionalLastName", "professionalCategoryName", "date", "startTime", "expiresAt", "rejectionReason",
                "reservationId", "confirmedAt", "cancelledAt", "cancellationReason");
        assertThat(body).doesNotContain("hold-1").doesNotContain("request-event-1").doesNotContain("5492615551234");
    }

    // Lo que se puede pedir por filtro son exactamente los estados que se pueden listar
    @ParameterizedTest
    @EnumSource(ReservationStatus.class)
    void theFilterAcceptsExactlyTheStatusesThatCanBeListed(ReservationStatus status) throws Exception {
        when(reservationService.findReservationProcessesByUser(42L, status, 0, 10)).thenReturn(new ReservationProcessPage(List.of(), 0));

        list("?status=" + status.name()).andExpect(status().is(status.isListable() ? 200 : 400));
    }

    @ParameterizedTest
    @CsvSource({"status, unknown", "status, confirmed", "status, EXPIRED", "status, INVALID", "page, -1", "page, abc",
            "page, 99999999999", "size, 0", "size, 101", "size, -1", "size, abc", "size, 1.5"})
    void anInvalidParameterIsAValidationErrorWithItsFieldAndNeverReachesTheService(String field, String value) throws Exception {
        expectProblem(list("?" + field + "=" + value), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field));

        verifyNoInteractions(reservationService);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "0, 100", "2147483647, 100"})
    void theLimitsOfThePageAndSizeAreAccepted(int page, int size) throws Exception {
        when(reservationService.findReservationProcessesByUser(42L, null, page, size)).thenReturn(new ReservationProcessPage(List.of(), 0));

        list("?page=" + page + "&size=" + size).andExpect(status().isOk());
    }

    @Test
    void aFailureReadingTheListIsAnInternalErrorWithoutTheReason() throws Exception {
        when(reservationService.findReservationProcessesByUser(42L, null, 0, 10)).thenThrow(new IllegalStateException("database down"));

        String body = expectProblem(list(""), 500, "INTERNAL_ERROR").andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("database down");
    }

    @Test
    void withoutATokenNothingIsListed() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void anInvalidTokenListsNothing() throws Exception {
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void anExpiredTokenListsNothing() throws Exception {
        Instant now = Instant.now();
        String expired = TestTokens.signed(claims -> claims.subject("42").claim("auth", "ROLE_USER")
                .issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(1)));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)).andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void aTokenWithoutAValidRoleListsNothing() throws Exception {
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forUser("42", "ROLE_OTRO")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reservationService);
    }

    @Test
    void aCancellationAnswersTheProcessWithoutPrivateData() throws Exception {
        ReservationProcess cancelled = submittedProcess();
        cancelled.setStatus(ReservationStatus.CANCELLED);
        cancelled.setCancelledAt(Instant.parse("2026-11-04T15:10:00.987Z"));
        cancelled.setCancellationReason("Cambio");
        when(reservationService.cancelReservationProcess(42L, "process-1", "Cambio")).thenReturn(cancelled);

        String body = cancel("process-1", "{\"reason\":\"Cambio\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value("2026-11-04T15:10:00Z"))
                .andExpect(jsonPath("$.cancellationReason").value("Cambio"))
                .andExpect(jsonPath("$.expiresAt").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("hold-1").doesNotContain("request-event-1").doesNotContain("5492615551234")
                .doesNotContain("needsReconciliation");
    }

    @Test
    void theOwnerOfTheCancellationComesFromTheTokenAndTheProcessFromThePath() throws Exception {
        when(reservationService.cancelReservationProcess(7L, "process-9", null)).thenReturn(HELD);

        mockMvc.perform(post(PATH + "/process-9/cancel").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forUser("7", "ROLE_ADMIN")))
                .andExpect(status().isOk());

        verify(reservationService).cancelReservationProcess(7L, "process-9", null);
    }

    @Test
    void withoutABodyTheCancellationHasNoReason() throws Exception {
        when(reservationService.cancelReservationProcess(42L, "process-1", null)).thenReturn(HELD);

        mockMvc.perform(post(PATH + "/process-1/cancel").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk());

        verify(reservationService).cancelReservationProcess(42L, "process-1", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}", "{\"other\":\"ignored\"}"})
    void aBodyWithoutAUsefulReasonHasNoReason(String body) throws Exception {
        when(reservationService.cancelReservationProcess(42L, "process-1", null)).thenReturn(HELD);

        cancel("process-1", body).andExpect(status().isOk());

        verify(reservationService).cancelReservationProcess(42L, "process-1", null);
    }

    @Test
    void aReasonOfTheMaximumLengthIsAccepted() throws Exception {
        String reason = "x".repeat(500);
        when(reservationService.cancelReservationProcess(42L, "process-1", reason)).thenReturn(HELD);

        cancel("process-1", "{\"reason\":\"" + reason + "\"}").andExpect(status().isOk());
    }

    @Test
    void aLongerReasonIsAValidationErrorAndNeverReachesTheService() throws Exception {
        expectProblem(cancel("process-1", "{\"reason\":\"" + "x".repeat(501) + "\"}"), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));

        verifyNoInteractions(reservationService);
    }

    @Test
    void aBadlyWrittenBodyNeverReachesTheService() throws Exception {
        expectProblem(cancel("process-1", "{not json"), 400, "VALIDATION_ERROR");

        verifyNoInteractions(reservationService);
    }

    @ParameterizedTest
    @CsvSource({"ReservationProcessNotFoundException, 404, RESERVATION_PROCESS_NOT_FOUND",
            "ReservationNotCancellableException, 409, RESERVATION_NOT_CANCELLABLE",
            "ReservationServiceDegradedException, 503, SERVICE_DEGRADED",
            "ReservationCentralUnavailableException, 503, CENTRAL_UNAVAILABLE",
            "ReservationLocalStateOutOfSyncException, 500, LOCAL_STATE_OUT_OF_SYNC",
            "ReservationAppointmentNotFoundException, 500, INTERNAL_ERROR",
            "ReservationAppointmentOwnerMismatchException, 500, INTERNAL_ERROR",
            "ReservationIntegrationException, 500, INTERNAL_ERROR",
            "ReservationNotSavedException, 500, INTERNAL_ERROR"})
    void eachCancellationErrorAnswersItsStatusAndCodeWithoutTheInternalReason(String exception, int status, String code) throws Exception {
        RuntimeException failure = switch (exception) {
            case "ReservationProcessNotFoundException" -> new ReservationProcessNotFoundException("secret-process");
            case "ReservationNotCancellableException" -> new ReservationNotCancellableException("secret-process");
            case "ReservationServiceDegradedException" -> new ReservationServiceDegradedException("secret-reason");
            case "ReservationCentralUnavailableException" -> new ReservationCentralUnavailableException("secret-reason");
            case "ReservationLocalStateOutOfSyncException" -> new ReservationLocalStateOutOfSyncException("secret-process", new IllegalStateException("secret-cause"));
            case "ReservationAppointmentNotFoundException" -> new ReservationAppointmentNotFoundException("secret-process");
            case "ReservationAppointmentOwnerMismatchException" -> new ReservationAppointmentOwnerMismatchException("secret-process");
            case "ReservationIntegrationException" -> new ReservationIntegrationException("secret-reason");
            default -> new ReservationNotSavedException("secret-process", new IllegalStateException("secret-cause"));
        };
        when(reservationService.cancelReservationProcess(42L, "process-1", null)).thenThrow(failure);

        String body = expectProblem(cancel("process-1", "{}"), status, code).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret");
    }

    @Test
    void aProcessThatCannotBeCancelledAnswersItsCurrentStatus() throws Exception {
        when(reservationService.cancelReservationProcess(42L, "process-1", null))
                .thenThrow(new ReservationInvalidProcessStateException("process-1", ReservationStatus.PHONE_SUBMITTED));

        expectProblem(cancel("process-1", "{}"), 409, "INVALID_PROCESS_STATE")
                .andExpect(jsonPath("$.currentStatus").value("PHONE_SUBMITTED"));
    }

    @Test
    void thePhoneInTheCauseOfAFailedCancellationNeverReachesTheLog(CapturedOutput output) throws Exception {
        when(reservationService.cancelReservationProcess(42L, "process-1", null))
                .thenThrow(new ReservationNotSavedException("process-1", new IllegalStateException("row with +5492615551234")))
                .thenThrow(new ReservationLocalStateOutOfSyncException("process-1", new IllegalStateException("row with +5492615551234")));

        expectProblem(cancel("process-1", "{}"), 500, "INTERNAL_ERROR");
        expectProblem(cancel("process-1", "{}"), 500, "LOCAL_STATE_OUT_OF_SYNC");

        assertThat(output.getAll()).doesNotContain("5492615551234");
    }

    @Test
    void withoutATokenNothingIsCancelled() throws Exception {
        mockMvc.perform(post(PATH + "/process-1/cancel")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void anInvalidTokenCancelsNothing() throws Exception {
        mockMvc.perform(post(PATH + "/process-1/cancel").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void anExpiredTokenCancelsNothing() throws Exception {
        Instant now = Instant.now();
        String expired = TestTokens.signed(claims -> claims.subject("42").claim("auth", "ROLE_USER")
                .issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(1)));

        mockMvc.perform(post(PATH + "/process-1/cancel").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    void aTokenWithoutAValidRoleCancelsNothing() throws Exception {
        mockMvc.perform(post(PATH + "/process-1/cancel").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forUser("42", "ROLE_OTRO")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reservationService);
    }

    private ResultActions cancel(String reservationProcessId, String body) throws Exception {
        return mockMvc.perform(post(PATH + "/" + reservationProcessId + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions list(String parameters) throws Exception {
        return mockMvc.perform(get(PATH + parameters).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));
    }

    private ResultActions query(String reservationProcessId, String token) throws Exception {
        return mockMvc.perform(get(PATH + "/" + reservationProcessId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static ReservationProcess submittedProcess() {
        return ReservationProcess.builder()
                .reservationProcessId("process-1").holdId("hold-1").userId(42L).professionalId(201L)
                .professionalFirstName("Ana").professionalLastName("Gomez").professionalCategoryName("Clinica medica")
                .date(DATE).startTime(TEN_THIRTY).status(ReservationStatus.PHONE_SUBMITTED)
                .expiresAt(Instant.parse("2026-10-27T04:44:38.627744064Z")).requestEventId("request-event-1")
                .phoneNumber("+5492615551234").needsReconciliation(true).build();
    }

    private ResultActions submitPhone(String reservationProcessId, String body) throws Exception {
        return mockMvc.perform(post(PATH + "/" + reservationProcessId + "/phone")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body));
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
