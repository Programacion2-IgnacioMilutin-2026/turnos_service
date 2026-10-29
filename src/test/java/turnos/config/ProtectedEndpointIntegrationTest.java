package turnos.config;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import turnos.TestTokens;
import turnos.TestcontainersConfiguration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// La aplicación completa en un puerto real, con Postgres real y la clave privada de test como única clave:
// registra con T01, inicia sesión con T02 y usa ese token en un endpoint protegido
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, ProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class ProtectedEndpointIntegrationTest {

    private static final String REGISTER_BODY = """
            {"login":"maria.gomez","password":"secreta123","firstName":"María","lastName":"Gómez",
             "email":"maria.gomez@example.com","langKey":"es"}
            """;
    private static final String CREDENTIALS = "{\"username\":\"maria.gomez\",\"password\":\"secreta123\"}";

    @Autowired
    private RestTestClient restTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyTheUsers() {
        jdbcTemplate.update("DELETE FROM user_authorities");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void aUserWhoLoggedInReachesAProtectedEndpointWithTheirOwnId() {
        register(null).expectStatus().isCreated();
        String token = loginToken();

        String body = probe("Bearer " + token).expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<Integer>read(body, "$.userId").longValue()).isEqualTo(idOf("maria.gomez"));
        assertThat(JsonPath.<String>read(body, "$.token")).isEqualTo(token);
    }

    @Test
    void withoutTokenTheProtectedEndpointAnswersUnauthorized() {
        probe(null)
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    // Una ruta protegida que no existe: sin token no se revela nada; con token pasa la seguridad
    @Test
    void aRouteThatDoesNotExistAnswersUnauthorizedWithoutTokenAndNotFoundWithIt() {
        register(null).expectStatus().isCreated();
        String token = loginToken();

        restTestClient.get().uri("/api/no-existe").exchange().expectStatus().isUnauthorized();
        restTestClient.get().uri("/api/no-existe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void thePublicEndpointsIgnoreAnyAuthorizationHeader() {
        for (String header : authorizationHeaders()) {
            emptyTheUsers();
            register(header).expectStatus().isCreated();
            authenticate(CREDENTIALS, header).expectStatus().isOk();
        }
    }

    @Test
    void aWrongPasswordGetsTheSameAnswerWithOrWithoutAuthorizationHeader() {
        register(null).expectStatus().isCreated();
        String wrongPassword = "{\"username\":\"maria.gomez\",\"password\":\"otra-clave\"}";
        String withoutHeader = rejectedBody(wrongPassword, null);

        for (String header : authorizationHeaders()) {
            assertThat(rejectedBody(wrongPassword, header)).isEqualTo(withoutHeader);
        }
        assertThat(JsonPath.<String>read(withoutHeader, "$.code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void theTokensNeverReachTheOutput(CapturedOutput output) {
        register(null).expectStatus().isCreated();
        String token = loginToken();
        List<String> usedTokens = new ArrayList<>(List.of(token, expiredToken()));
        probe("Bearer " + token).expectStatus().isOk();
        probe("Bearer " + expiredToken()).expectStatus().isUnauthorized();
        probe("Bearer " + TestTokens.withPayload(token, "{\"sub\":\"7\"}")).expectStatus().isUnauthorized();
        authenticate(CREDENTIALS, "Bearer " + expiredToken()).expectStatus().isOk();

        for (String used : usedTokens) {
            assertThat(output.getAll()).doesNotContain(used);
        }
    }

    private List<String> authorizationHeaders() {
        return List.of(
                "Bearer " + TestTokens.forUser("42", "ROLE_USER"),
                "Bearer xxx",
                "Bearer " + expiredToken());
    }

    private String expiredToken() {
        Instant expiredAt = Instant.parse("2026-01-01T00:00:00Z");
        return TestTokens.signed(claims -> claims.subject("42").claim("auth", "ROLE_USER")
                .issuedAt(expiredAt.minusSeconds(3600)).expiresAt(expiredAt));
    }

    private RestTestClient.ResponseSpec register(String authorization) {
        RestTestClient.RequestBodySpec request = restTestClient.post().uri("/api/register")
                .contentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.body(REGISTER_BODY).exchange();
    }

    private RestTestClient.ResponseSpec authenticate(String credentials, String authorization) {
        RestTestClient.RequestBodySpec request = restTestClient.post().uri("/api/authenticate")
                .contentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.body(credentials).exchange();
    }

    private String loginToken() {
        String body = authenticate(CREDENTIALS, null).expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        return JsonPath.read(body, "$.id_token");
    }

    private String rejectedBody(String credentials, String authorization) {
        return authenticate(credentials, authorization).expectStatus().isUnauthorized()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private RestTestClient.ResponseSpec probe(String authorization) {
        RestTestClient.RequestHeadersSpec<?> request = restTestClient.get().uri("/api/probe");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange();
    }

    private long idOf(String login) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE login = ?", Long.class, login);
    }
}
