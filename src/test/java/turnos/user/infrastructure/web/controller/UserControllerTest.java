package turnos.user.infrastructure.web.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import turnos.config.SecurityConfig;
import turnos.user.application.exception.UserEmailAlreadyExistsException;
import turnos.user.application.exception.UserLoginAlreadyExistsException;
import turnos.user.application.service.UserService;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;
import turnos.user.infrastructure.web.mapper.UserDtoMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, UserDtoMapper.class})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void registersAndReturnsOnlyThePublicData() throws Exception {
        given(userService.registerUser(any(), anyString())).willAnswer(invocation -> savedVersionOf(invocation.getArgument(0)));

        register(validBody())
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1042))
                .andExpect(jsonPath("$.login").value("maria.gomez"))
                .andExpect(jsonPath("$.firstName").value("María"))
                .andExpect(jsonPath("$.lastName").value("Gómez"))
                .andExpect(jsonPath("$.email").value("maria.gomez@example.com"))
                .andExpect(jsonPath("$.imageUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.langKey").value("es"))
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.authorities").doesNotExist())
                .andExpect(jsonPath("$.createdDate").doesNotExist())
                .andExpect(jsonPath("$.lastModifiedDate").doesNotExist());
    }

    @Test
    void sendsThePasswordApartAndIgnoresTheFieldsManagedByTheBackend() throws Exception {
        given(userService.registerUser(any(), anyString())).willAnswer(invocation -> savedVersionOf(invocation.getArgument(0)));
        Map<String, String> body = validBody();
        String json = toJson(body).replaceFirst("\\{", "{\"id\":999,\"activated\":false,\"authorities\":[\"ROLE_ADMIN\"],");

        mockMvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userService).registerUser(userCaptor.capture(), eq("secreta123"));
        User received = userCaptor.getValue();
        assertThat(received.getId()).isNull();
        assertThat(received.getPasswordHash()).isNull();
        assertThat(received.getAuthorities()).isNull();
        assertThat(received.isActivated()).isFalse();
    }

    @Test
    void repeatedLoginAnswersUsernameAlreadyExists() throws Exception {
        given(userService.registerUser(any(), anyString())).willThrow(new UserLoginAlreadyExistsException("maria.gomez"));

        expectProblem(register(validBody()), 400, "Bad Request", "USERNAME_ALREADY_EXISTS");
    }

    @Test
    void repeatedEmailAnswersEmailAlreadyExists() throws Exception {
        given(userService.registerUser(any(), anyString())).willThrow(new UserEmailAlreadyExistsException("maria.gomez@example.com"));

        expectProblem(register(validBody()), 400, "Bad Request", "EMAIL_ALREADY_EXISTS");
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("login", null),
                Arguments.of("login", "ab"),
                Arguments.of("login", "a".repeat(51)),
                Arguments.of("login", "maria gomez"),
                Arguments.of("password", null),
                Arguments.of("password", "abc"),
                Arguments.of("password", "a".repeat(101)),
                Arguments.of("firstName", null),
                Arguments.of("firstName", "M"),
                Arguments.of("firstName", "a".repeat(51)),
                Arguments.of("lastName", null),
                Arguments.of("lastName", " "),
                Arguments.of("lastName", "a".repeat(51)),
                Arguments.of("email", null),
                Arguments.of("email", "no-es-un-email"),
                Arguments.of("email", "a".repeat(250) + "@x.com"),
                Arguments.of("langKey", null),
                Arguments.of("langKey", "e"),
                Arguments.of("langKey", "a".repeat(11)));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidFields")
    void invalidFieldAnswersValidationErrorWithoutCallingTheService(String field, String value) throws Exception {
        Map<String, String> body = validBody();
        if (value == null) {
            body.remove(field);
        } else {
            body.put(field, value);
        }

        expectProblem(register(body), 400, "Bad Request", "VALIDATION_ERROR")
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem(field)))
                .andExpect(jsonPath("$.fieldErrors[*].message").isNotEmpty());
        verifyNoInteractions(userService);
    }

    static Stream<Arguments> validLimitValues() {
        return Stream.of(
                Arguments.of("login", "abc"),
                Arguments.of("login", "a".repeat(50)),
                Arguments.of("login", "maria_gomez-1.a@b"),
                Arguments.of("login", "maria@example.com"),
                Arguments.of("password", "abcd"),
                Arguments.of("password", "ñ".repeat(100)),
                Arguments.of("firstName", "Al"),
                Arguments.of("langKey", "es"),
                Arguments.of("langKey", "a".repeat(10)),
                Arguments.of("imageUrl", null));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("validLimitValues")
    void acceptsTheLimitValues(String field, String value) throws Exception {
        given(userService.registerUser(any(), anyString())).willAnswer(invocation -> savedVersionOf(invocation.getArgument(0)));
        Map<String, String> body = validBody();
        if (value == null) {
            body.remove(field);
        } else {
            body.put(field, value);
        }

        register(body).andExpect(status().isCreated());
    }

    @Test
    void malformedJsonAnswersValidationErrorWithoutCallingTheService() throws Exception {
        ResultActions result = mockMvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"maria.gomez\","));

        expectProblem(result, 400, "Bad Request", "VALIDATION_ERROR");
        verifyNoInteractions(userService);
    }

    // La validación evalúa todas las reglas del campo, también la expresión regular, aunque el largo ya falle
    @Test
    void aHugeLoginAnswersValidationErrorInsteadOfOverflowingTheStack() throws Exception {
        Map<String, String> body = validBody();
        body.put("login", "a@" + "b.".repeat(500_000) + "c");

        expectProblem(register(body), 400, "Bad Request", "VALIDATION_ERROR")
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("login")));
        verifyNoInteractions(userService);
    }

    @Test
    void aMissingBodyAnswersValidationErrorWithoutCallingTheService() throws Exception {
        ResultActions result = mockMvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON));

        expectProblem(result, 400, "Bad Request", "VALIDATION_ERROR");
        verifyNoInteractions(userService);
    }

    @Test
    void unexpectedErrorAnswersInternalErrorWithoutDetails() throws Exception {
        given(userService.registerUser(any(), anyString())).willThrow(new IllegalStateException("database password is wrong"));

        expectProblem(register(validBody()), 500, "Internal Server Error", "INTERNAL_ERROR")
                .andExpect(jsonPath("$.detail").value(not(containsString("database"))));
    }

    private ResultActions register(Map<String, String> body) throws Exception {
        return mockMvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
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

    private Map<String, String> validBody() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("login", "maria.gomez");
        body.put("password", "secreta123");
        body.put("firstName", "María");
        body.put("lastName", "Gómez");
        body.put("email", "maria.gomez@example.com");
        body.put("langKey", "es");
        body.put("imageUrl", "https://example.com/avatar.png");
        return body;
    }

    private String toJson(Map<String, String> body) {
        return body.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    // Devuelve un objeto nuevo: si modificara el recibido, el captor vería los datos ya "guardados"
    private User savedVersionOf(User user) {
        return User.builder()
                .id(1042L)
                .login(user.getLogin())
                .passwordHash("hash")
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .imageUrl(user.getImageUrl())
                .langKey(user.getLangKey())
                .activated(true)
                .authorities(Set.of(Authority.ROLE_USER))
                .createdDate(Instant.parse("2026-10-17T12:00:00Z"))
                .lastModifiedDate(Instant.parse("2026-10-17T12:00:00Z"))
                .build();
    }
}
