package turnos.user.infrastructure.web.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import turnos.config.SecurityConfig;
import turnos.user.application.exception.UserInvalidCredentialsException;
import turnos.user.application.service.UserService;
import turnos.user.infrastructure.web.mapper.UserDtoMapper;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, UserDtoMapper.class})
class UserControllerAuthenticateTest {

    private static final String VALID_BODY = "{\"username\":\"maria.gomez\",\"password\":\"secreta123\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void validCredentialsReturnOnlyTheToken() throws Exception {
        given(userService.authenticateUser("maria.gomez", "secreta123")).willReturn("header.payload.signature");

        authenticate(VALID_BODY)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id_token").value("header.payload.signature"))
                .andExpect(jsonPath("$.idToken").doesNotExist())
                .andExpect(jsonPath("$.length()").value(1));
    }

    static Stream<Arguments> missingFields() {
        return Stream.of(
                Arguments.of("username", "{\"password\":\"secreta123\"}"),
                Arguments.of("username", "{\"username\":null,\"password\":\"secreta123\"}"),
                Arguments.of("username", "{\"username\":\"\",\"password\":\"secreta123\"}"),
                Arguments.of("password", "{\"username\":\"maria.gomez\"}"),
                Arguments.of("password", "{\"username\":\"maria.gomez\",\"password\":null}"),
                Arguments.of("password", "{\"username\":\"maria.gomez\",\"password\":\"\"}"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("missingFields")
    void aMissingFieldAnswersValidationErrorWithoutCallingTheService(String field, String body) throws Exception {
        expectProblem(authenticate(body), 400, "Bad Request", "VALIDATION_ERROR")
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem(field)));
        verifyNoInteractions(userService);
    }

    @Test
    void aUsernameOfOnlySpacesIsNotMissing() throws Exception {
        given(userService.authenticateUser("   ", "secreta123")).willThrow(new UserInvalidCredentialsException());

        expectProblem(authenticate("{\"username\":\"   \",\"password\":\"secreta123\"}"), 401, "Unauthorized", "INVALID_CREDENTIALS");
    }

    @Test
    void invalidCredentialsAnswerUnauthorizedWithAGenericDetail() throws Exception {
        given(userService.authenticateUser(anyString(), anyString())).willThrow(new UserInvalidCredentialsException());

        expectProblem(authenticate(VALID_BODY), 401, "Unauthorized", "INVALID_CREDENTIALS")
                .andExpect(jsonPath("$.detail").value("Invalid credentials"));
    }

    @Test
    void rememberMeAndUnknownFieldsAreIgnored() throws Exception {
        given(userService.authenticateUser("maria.gomez", "secreta123")).willReturn("header.payload.signature");

        authenticate("{\"username\":\"maria.gomez\",\"password\":\"secreta123\",\"rememberMe\":true,\"id\":999}")
                .andExpect(status().isOk());
        verify(userService).authenticateUser("maria.gomez", "secreta123");
    }

    @Test
    void anInvalidAuthorizationHeaderIsIgnored() throws Exception {
        given(userService.authenticateUser("maria.gomez", "secreta123")).willReturn("header.payload.signature");

        mockMvc.perform(post("/api/authenticate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer xxx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id_token").value("header.payload.signature"));
    }

    @Test
    void aSigningFailureAnswersInternalErrorWithoutDetails() throws Exception {
        given(userService.authenticateUser(anyString(), anyString())).willThrow(new JwtEncodingException("signing key rejected"));

        expectProblem(authenticate(VALID_BODY), 500, "Internal Server Error", "INTERNAL_ERROR")
                .andExpect(jsonPath("$.detail").value(not(containsString("signing"))));
    }

    @Test
    void aDatabaseFailureAnswersInternalErrorWithoutDetails() throws Exception {
        given(userService.authenticateUser(anyString(), anyString())).willThrow(new DataAccessResourceFailureException("connection refused"));

        expectProblem(authenticate(VALID_BODY), 500, "Internal Server Error", "INTERNAL_ERROR")
                .andExpect(jsonPath("$.detail").value(not(containsString("connection"))));
    }

    @Test
    void malformedJsonAnswersValidationErrorWithoutCallingTheService() throws Exception {
        expectProblem(authenticate("{\"username\":\"maria.gomez\","), 400, "Bad Request", "VALIDATION_ERROR");
        verifyNoInteractions(userService);
    }

    private ResultActions authenticate(String body) throws Exception {
        return mockMvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions expectProblem(ResultActions result, int status, String title, String code) throws Exception {
        return result
                .andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }
}
