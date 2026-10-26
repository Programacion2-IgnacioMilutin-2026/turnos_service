package turnos.availability.infrastructure.web.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import turnos.TestSecurityConfiguration;
import turnos.TestTokens;
import turnos.availability.application.exception.AvailabilityCatalogUnavailableException;
import turnos.availability.application.exception.AvailabilityCentralUnavailableException;
import turnos.availability.application.exception.AvailabilityDateInPastException;
import turnos.availability.application.exception.AvailabilityIntegrationException;
import turnos.availability.application.exception.AvailabilityProfessionalDisabledException;
import turnos.availability.application.exception.AvailabilityProfessionalNotFoundException;
import turnos.availability.application.exception.AvailabilityServiceDegradedException;
import turnos.availability.application.service.AvailabilityService;
import turnos.availability.domain.model.FreeSlot;
import turnos.availability.domain.model.FreeSlotsResult;
import turnos.availability.infrastructure.web.mapper.AvailabilityDtoMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@WebMvcTest(AvailabilityController.class)
@Import({TestSecurityConfiguration.class, AvailabilityDtoMapper.class})
class AvailabilityControllerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 26);
    private static final String TOKEN = TestTokens.forUser("7", "ROLE_USER");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AvailabilityService availabilityService;

    @Test
    void freeSlotsAreAnsweredInOrderWithHourAndMinute() throws Exception {
        when(availabilityService.getFreeSlots(201L, DATE, TOKEN)).thenReturn(FreeSlotsResult.of(List.of(
                new FreeSlot(LocalTime.of(12, 0), 30), new FreeSlot(LocalTime.of(12, 30), 30)), false,
                Instant.parse("2026-10-25T15:00:12.345678Z")));

        request("professionalId=201&date=2026-10-26")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(2))
                .andExpect(jsonPath("$.slots[0].professionalId").value(201))
                .andExpect(jsonPath("$.slots[0].date").value("2026-10-26"))
                .andExpect(jsonPath("$.slots[0].startTime").value("12:00"))
                .andExpect(jsonPath("$.slots[0].durationMinutes").value(30))
                .andExpect(jsonPath("$.slots[1].startTime").value("12:30"))
                .andExpect(jsonPath("$.emptyReason").isEmpty())
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.dataAsOf").value("2026-10-25T15:00:12Z"));
        verify(availabilityService).getFreeSlots(201L, DATE, TOKEN);
    }

    @Test
    void anEmptyAnswerFromTheCacheCarriesItsReasonAndStaleFlag() throws Exception {
        when(availabilityService.getFreeSlots(201L, DATE, TOKEN))
                .thenReturn(FreeSlotsResult.of(List.of(), true, Instant.parse("2026-10-25T14:10:00Z")));

        request("professionalId=201&date=2026-10-26")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(0))
                .andExpect(jsonPath("$.emptyReason").value("NO_FREE_SLOTS"))
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.dataAsOf").value("2026-10-25T14:10:00Z"));
    }

    @Test
    void missingParametersAreValidationErrors() throws Exception {
        request("")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'professionalId')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'date')]").exists());
        verifyNoInteractions(availabilityService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "99999999999999999999", "1.5"})
    void anInvalidProfessionalIdIsAValidationErrorWithoutJavaTypes(String professionalId) throws Exception {
        request("professionalId=" + professionalId + "&date=2026-10-26")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("professionalId"))
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("Long"))));
        verifyNoInteractions(availabilityService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void aProfessionalIdThatIsNotPositiveHasItsOwnMessage(String professionalId) throws Exception {
        request("professionalId=" + professionalId + "&date=2026-10-26")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Professional id must be a positive number"));
    }

    @Test
    void missingParametersHaveTheirOwnMessages() throws Exception {
        request("")
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'professionalId')].message").value(org.hamcrest.Matchers.hasItem("Professional id is mandatory")))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'date')].message").value(org.hamcrest.Matchers.hasItem("Date is mandatory")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"20-10-2026", "2026-02-30", "2026-10-26T10:00", "hoy"})
    void anInvalidDateIsAValidationErrorWithoutJavaTypes(String date) throws Exception {
        request("professionalId=201&date=" + date)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("date"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("invalid value"))
                .andExpect(content().string(not(containsString("java."))));
        verifyNoInteractions(availabilityService);
    }

    // El idioma del cliente o del servidor no cambia los mensajes: siempre en inglés
    @Test
    void validationMessagesAreInEnglishWhateverLanguageTheClientAsksFor() throws Exception {
        mockMvc.perform(get("/api/free-slots?professionalId=201").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "es-AR,es;q=0.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("date"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Date is mandatory"));
    }

    @Test
    void aDateBeforeTodayIsAValidationErrorOnTheDate() throws Exception {
        when(availabilityService.getFreeSlots(anyLong(), any(), anyString())).thenThrow(new AvailabilityDateInPastException(DATE));

        request("professionalId=201&date=2026-10-26")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("date"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must not be before today"));
    }

    @Test
    void eachErrorHasItsStatusAndCodeWithoutInternalDetails() throws Exception {
        expectError(new AvailabilityProfessionalNotFoundException(201L), 404, "PROFESSIONAL_NOT_FOUND");
        expectError(new AvailabilityProfessionalDisabledException(201L), 409, "PROFESSIONAL_DISABLED");
        expectError(new AvailabilityCatalogUnavailableException("Catalog answered HTTP 503"), 503, "CATALOG_UNAVAILABLE");
        expectError(new AvailabilityServiceDegradedException("Catedra answered STUDENT_INTEGRATION_NOT_FOUND"), 503, "SERVICE_DEGRADED");
        expectError(new AvailabilityCentralUnavailableException("Catedra answered HTTP 503"), 503, "CENTRAL_UNAVAILABLE");
        expectError(new AvailabilityIntegrationException("Catalog rejected the forwarded user token"), 500, "INTERNAL_ERROR");
    }

    @Test
    void withoutATokenTheServiceIsNeverCalled() throws Exception {
        mockMvc.perform(get("/api/free-slots?professionalId=201&date=2026-10-26")).andExpect(status().isUnauthorized());
        verifyNoInteractions(availabilityService);
    }

    private void expectError(RuntimeException error, int status, String code) throws Exception {
        doThrow(error).when(availabilityService).getFreeSlots(anyLong(), any(), anyString());

        request("professionalId=201&date=2026-10-26")
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(content().string(not(containsString("Catalog answered"))))
                .andExpect(content().string(not(containsString("Catedra answered"))))
                .andExpect(content().string(not(containsString("forwarded"))));
    }

    private ResultActions request(String query) throws Exception {
        return mockMvc.perform(get("/api/free-slots?" + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));
    }
}
