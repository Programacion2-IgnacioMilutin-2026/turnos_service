package turnos.reservation;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import turnos.TestTokens;
import turnos.TestcontainersConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

// Postgres caído de verdad, con su contenedor pausado: deja de responder sin cerrar las conexiones. Esta clase tiene su
// propio contenedor: pausarlo no afecta a los demás tests. Sin el límite de espera de la base, una consulta se queda
// esperando para siempre: el tiempo máximo de cada test lo convierte en una falla
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, ControllableProcessRepository.Configuration.class})
@ExtendWith(OutputCaptureExtension.class)
@Timeout(value = 45, unit = TimeUnit.SECONDS)
class DatabaseDownStatusQueryIntegrationTest {

    private static final String PROCESS = "process-1";
    private static final String PHONE = "+5492615551234";

    // Esperas cortas para que el test sea rápido: la base caída se detecta enseguida
    @DynamicPropertySource
    static void shortDatabaseWaits(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "500");
        registry.add("TURNOS_DB_SOCKET_TIMEOUT", () -> "2");
    }

    @Autowired
    private RestTestClient restTestClient;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PostgreSQLContainer postgres;
    @Autowired
    private ControllableProcessRepository database;

    private String token;
    private Long userId;

    @BeforeEach
    void createUser() {
        database.reset();
        userId = jdbc.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated,
                                   created_date, last_modified_date)
                VALUES ('juan.perez', 'hash', 'Juan', 'Perez', 'juan@example.com', 'es', true, now(), now())
                RETURNING id
                """, Long.class);
        token = TestTokens.forUser(userId.toString(), "ROLE_USER");
    }

    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM reservation_processes");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void anOverdueProcessIsClosedByTheFirstQueryAfterTheDatabaseComesBack(CapturedOutput output) throws InterruptedException {
        insertOverdueProcess("WAITING_FOR_PHONE");

        expectInternalErrorWithTheDatabaseDown(output);

        assertThat(JsonPath.<String>read(queryWhenTheDatabaseIsBack(), "$.status")).isEqualTo("EXPIRED");
        assertThat(stored()).containsEntry("status", "EXPIRED");
    }

    @Test
    void anOverdueProcessIsMarkedByTheFirstQueryAfterTheDatabaseComesBack(CapturedOutput output) throws InterruptedException {
        insertOverdueProcess("PHONE_SUBMITTED");

        expectInternalErrorWithTheDatabaseDown(output);

        assertThat(JsonPath.<String>read(queryWhenTheDatabaseIsBack(), "$.status")).isEqualTo("PHONE_SUBMITTED");
        assertThat(stored()).containsEntry("status", "PHONE_SUBMITTED").containsEntry("needs_reconciliation", true);
    }

    // Una conexión recién usada se entrega sin comprobar: la lectura llega a salir y nunca recibe respuesta
    @Test
    void aReadOnAConnectionThatStoppedRespondingEndsInAnInternalError(CapturedOutput output) {
        insertOverdueProcess("WAITING_FOR_PHONE");
        Map<String, Object> before = stored();

        pauseDatabase();
        try {
            expectInternalError();
        } finally {
            unpauseDatabase();
        }

        assertThat(output.getAll()).doesNotContain("5492615551234");
        expectUnchanged(before);
        assertThat(JsonPath.<String>read(queryWhenTheDatabaseIsBack(), "$.status")).isEqualTo("EXPIRED");
    }

    // Lo mismo después de las dos lecturas, con el candado tomado: tiene que terminar, y soltarlo
    @Test
    void aSaveOnADatabaseThatStoppedRespondingEndsInAnInternalErrorAndReleasesTheLock(CapturedOutput output) {
        insertOverdueProcess("WAITING_FOR_PHONE");
        Map<String, Object> before = stored();
        database.afterNextOwnerRead(() -> database.afterNextOwnerRead(this::pauseDatabase));

        try {
            expectInternalError();
        } finally {
            unpauseDatabase();
        }

        assertThat(output.getAll()).contains("could not be saved").doesNotContain("5492615551234");
        expectUnchanged(before);
        assertThat(JsonPath.<String>read(queryWhenTheDatabaseIsBack(), "$.status")).isEqualTo("EXPIRED");
        assertThat(stored()).containsEntry("status", "EXPIRED");
    }

    @Test
    void aListWithTheDatabaseDownIsAnInternalErrorAndAnswersWhenItComesBack(CapturedOutput output) throws InterruptedException {
        insertOverdueProcess("PHONE_SUBMITTED");
        Map<String, Object> before = stored();
        // Pasado este tiempo, el pool comprueba que la conexión siga viva antes de usarla: falla sin llegar a leer
        Thread.sleep(1000);

        pauseDatabase();
        try {
            expectInternalError(list());
        } finally {
            unpauseDatabase();
        }

        assertThat(output.getAll()).contains("Unexpected error").doesNotContain("5492615551234");
        expectUnchanged(before);
        expectTheProcessInTheList();
    }

    // Una conexión recién usada se entrega sin comprobar: la lectura llega a salir y nunca recibe respuesta
    @Test
    void aListOnAConnectionThatStoppedRespondingEndsInAnInternalError() {
        insertOverdueProcess("PHONE_SUBMITTED");
        Map<String, Object> before = stored();

        pauseDatabase();
        try {
            expectInternalError(list());
        } finally {
            unpauseDatabase();
        }

        expectUnchanged(before);
        expectTheProcessInTheList();
    }

    @Test
    void anAnswerThatTakesLongerThanTheLimitFailsInsteadOfWaiting() {
        Instant start = Instant.now();

        assertThatThrownBy(() -> jdbc.queryForObject("SELECT pg_sleep(10)", String.class))
                .isInstanceOf(DataAccessException.class);

        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(8));
    }

    private void expectInternalError() {
        expectInternalError(query());
    }

    private void expectInternalError(RestTestClient.ResponseSpec response) {
        String body = response.expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("INTERNAL_ERROR");
    }

    // La base puede tardar en volver a responder después de reanudarla
    private void expectUnchanged(Map<String, Object> before) {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                .untilAsserted(() -> assertThat(stored()).isEqualTo(before));
    }

    private void expectInternalErrorWithTheDatabaseDown(CapturedOutput output) throws InterruptedException {
        Map<String, Object> before = stored();
        // Pasado este tiempo, el pool comprueba que la conexión siga viva antes de usarla: falla sin llegar a leer
        Thread.sleep(1000);
        pauseDatabase();
        String body;
        try {
            body = query().expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                    .expectBody(String.class).returnResult().getResponseBody();
        } finally {
            unpauseDatabase();
        }
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("INTERNAL_ERROR");
        assertThat(JsonPath.<String>read(body, "$.detail")).isEqualTo("Internal server error");
        assertThat(output.getAll()).contains("Unexpected error").doesNotContain("5492615551234");
        expectUnchanged(before);
    }

    private String queryWhenTheDatabaseIsBack() {
        return query().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    private void insertOverdueProcess(String status) {
        jdbc.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    appointment_date, start_time, status, expires_at, phone_number)
                VALUES (?, 'hold-1', ?, 201, DATE '2026-11-09', TIME '12:30', ?, now() - interval '1 hour', ?)
                """, PROCESS, userId, status, PHONE);
    }

    private Map<String, Object> stored() {
        return jdbc.queryForMap("SELECT * FROM reservation_processes WHERE reservation_process_id = ?", PROCESS);
    }

    private void expectTheProcessInTheList() {
        String body = list().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonPath.<String>read(body, "$.items[0].reservationProcessId")).isEqualTo(PROCESS);
        assertThat(JsonPath.<Integer>read(body, "$.totalItems")).isEqualTo(1);
    }

    private RestTestClient.ResponseSpec list() {
        return restTestClient.get().uri("/api/reservation-processes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private RestTestClient.ResponseSpec query() {
        return restTestClient.get().uri("/api/reservation-processes/{id}", PROCESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private void pauseDatabase() {
        DockerClientFactory.instance().client().pauseContainerCmd(postgres.getContainerId()).exec();
    }

    private void unpauseDatabase() {
        DockerClientFactory.instance().client().unpauseContainerCmd(postgres.getContainerId()).exec();
    }
}
