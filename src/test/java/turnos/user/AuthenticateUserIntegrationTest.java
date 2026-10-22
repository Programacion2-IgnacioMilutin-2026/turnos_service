package turnos.user;

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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.client.RestTestClient;
import turnos.TestRsaKeys;
import turnos.TestcontainersConfiguration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// La aplicación completa en un puerto real, con Postgres real: registra con T01 e inicia sesión por HTTP
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthenticateUserIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void registerUsers() {
        jdbcTemplate.update("DELETE FROM user_authorities");
        jdbcTemplate.update("DELETE FROM users");
        register("maria.gomez");
        register("juan.perez");
    }

    @Test
    void loginReturnsATokenSignedWithTheServiceKey() {
        Jwt jwt = decode(tokenFrom(authenticate(credentials("maria.gomez", "secreta123"))));

        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
        assertThat(jwt.getClaims()).containsOnlyKeys("sub", "auth", "iat", "exp");
        assertThat(jwt.getSubject()).isEqualTo(idOf("maria.gomez"));
        assertThat(jwt.getClaimAsString("auth")).isEqualTo("ROLE_USER");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofSeconds(86_400));
    }

    @Test
    void theLoginIsComparedWithoutCase() {
        Jwt jwt = decode(tokenFrom(authenticate(credentials("Maria.Gomez", "secreta123"))));

        assertThat(jwt.getSubject()).isEqualTo(idOf("maria.gomez"));
    }

    @Test
    void wrongPasswordUnknownLoginAndInactiveAccountGetTheSameResponse() {
        jdbcTemplate.update("UPDATE users SET activated = false WHERE login = 'juan.perez'");

        String wrongPassword = rejectedBody(credentials("maria.gomez", "otra-clave"));
        String unknownLogin = rejectedBody(credentials("nadie", "secreta123"));
        String inactiveAccount = rejectedBody(credentials("juan.perez", "secreta123"));

        assertThat(JsonPath.<String>read(wrongPassword, "$.code")).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknownLogin).isEqualTo(wrongPassword);
        assertThat(inactiveAccount).isEqualTo(wrongPassword);
    }

    @Test
    void rememberMeDoesNotChangeTheValidity() {
        String body = "{\"username\":\"maria.gomez\",\"password\":\"secreta123\",\"rememberMe\":true}";

        Jwt jwt = decode(tokenFrom(authenticate(body)));

        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofSeconds(86_400));
    }

    @Test
    void anInvalidAuthorizationHeaderIsIgnored() {
        restTestClient.post().uri("/api/authenticate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer xxx")
                .contentType(MediaType.APPLICATION_JSON)
                .body(credentials("maria.gomez", "secreta123"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void neitherThePasswordNorTheHashNorTheTokenNorTheKeyReachTheOutput(CapturedOutput output) {
        String token = tokenFrom(authenticate(credentials("maria.gomez", "secreta123")));
        rejectedBody(credentials("maria.gomez", "otra-clave"));
        rejectedBody(credentials("nadie", "secreta123"));
        authenticate("{\"username\":\"maria.gomez\"}").expectStatus().isBadRequest();
        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE login = 'maria.gomez'", String.class);

        assertThat(output.getAll())
                .doesNotContain("secreta123")
                .doesNotContain("otra-clave")
                .doesNotContain(passwordHash)
                .doesNotContain(token)
                .doesNotContain("BEGIN PRIVATE KEY");
    }

    private void register(String login) {
        String body = """
                {"login":"%s","password":"secreta123","firstName":"María","lastName":"Gómez",
                 "email":"%s@example.com","langKey":"es"}
                """.formatted(login, login);
        restTestClient.post().uri("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated();
    }

    private RestTestClient.ResponseSpec authenticate(String body) {
        return restTestClient.post().uri("/api/authenticate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    private String tokenFrom(RestTestClient.ResponseSpec response) {
        String body = response.expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        return JsonPath.read(body, "$.id_token");
    }

    private String rejectedBody(String credentials) {
        return authenticate(credentials)
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private String credentials(String username, String password) {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
    }

    private String idOf(String login) {
        return String.valueOf(jdbcTemplate.queryForObject("SELECT id FROM users WHERE login = ?", Long.class, login));
    }

    private Jwt decode(String token) {
        return NimbusJwtDecoder.withPublicKey(TestRsaKeys.publicKey()).build().decode(token);
    }
}
