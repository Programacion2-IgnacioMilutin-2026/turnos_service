package turnos.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;
import turnos.TestcontainersConfiguration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// La aplicación completa en un puerto real, con Postgres real y sin nada simulado: los requests viajan por HTTP
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class RegisterUserIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM user_authorities");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void registersTheUserAndStoresOnlyTheHashOfThePassword() {
        register(body("Maria.Gomez", "Maria.Gomez@Example.com"))
                .expectStatus().isCreated()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.login").isEqualTo("maria.gomez")
                .jsonPath("$.firstName").isEqualTo("María")
                .jsonPath("$.email").isEqualTo("maria.gomez@example.com")
                .jsonPath("$.activated").isEqualTo(true)
                .jsonPath("$.passwordHash").doesNotExist();

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE login = 'maria.gomez'");
        String passwordHash = (String) row.get("password_hash");
        assertThat(passwordHash).isNotEqualTo("secreta123").hasSize(96);
        assertThat(Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().matches("secreta123", passwordHash)).isTrue();
        assertThat(row.get("activated")).isEqualTo(true);
        assertThat(row.get("created_date")).isNotNull();
        assertThat(row.get("last_modified_date")).isNotNull();
        assertThat(authoritiesOf(row.get("id"))).containsExactly("ROLE_USER");
    }

    @Test
    void rejectsARepeatedLoginEvenWithDifferentCase() {
        register(body("maria.gomez", "maria.gomez@example.com")).expectStatus().isCreated();

        register(body("MARIA.GOMEZ", "otra@example.com"))
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("USERNAME_ALREADY_EXISTS");

        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void rejectsARepeatedEmailEvenWithDifferentCase() {
        register(body("maria.gomez", "maria.gomez@example.com")).expectStatus().isCreated();

        register(body("otro.login", "MARIA.GOMEZ@EXAMPLE.COM"))
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("EMAIL_ALREADY_EXISTS");

        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidDataWithoutSavingAnything() {
        register(body("ab", "maria.gomez@example.com"))
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.fieldErrors[*].field").value(List.class, fields -> assertThat(fields).contains("login"));

        assertThat(countUsers()).isZero();
    }

    @Test
    void ignoresTheFieldsManagedByTheBackend() {
        String json = """
                {"id":999,"activated":false,"authorities":["ROLE_ADMIN"],
                 "login":"maria.gomez","password":"secreta123","firstName":"María","lastName":"Gómez",
                 "email":"maria.gomez@example.com","langKey":"es"}
                """;

        register(json)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.activated").isEqualTo(true);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE login = 'maria.gomez'");
        assertThat(row.get("id")).isNotEqualTo(999L);
        assertThat(row.get("activated")).isEqualTo(true);
        assertThat(authoritiesOf(row.get("id"))).containsExactly("ROLE_USER");
    }

    private RestTestClient.ResponseSpec register(String json) {
        return restTestClient.post().uri("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange();
    }

    private String body(String login, String email) {
        return """
                {"login":"%s","password":"secreta123","firstName":"María","lastName":"Gómez",
                 "email":"%s","langKey":"es","imageUrl":"https://example.com/avatar.png"}
                """.formatted(login, email);
    }

    private int countUsers() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class);
    }

    private List<String> authoritiesOf(Object userId) {
        return jdbcTemplate.queryForList("SELECT authority FROM user_authorities WHERE user_id = ?", String.class, userId);
    }
}
