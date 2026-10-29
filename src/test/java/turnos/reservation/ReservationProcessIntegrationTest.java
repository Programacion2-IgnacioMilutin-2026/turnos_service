package turnos.reservation;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;
import turnos.FakeHttpServer;
import turnos.TestTokens;
import turnos.TestcontainersConfiguration;
import turnos.availability.domain.model.AvailabilityPolicy;
import turnos.catedra.FakeCatedraServer;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;
import turnos.reservation.domain.model.ReservationPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa en un puerto real, con Postgres real y catálogo y cátedra simulados:
// registra con T01, inicia sesión con T02 y bloquea un turno con ese token
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ReservationProcessIntegrationTest {

    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();
    private static final String HOLDS_PATH = "/api/appointment-holds";
    // El hold de la grabación del hold
    private static final String CONFIRM_PATH = HOLDS_PATH + "/hold-fbeebd61-9202-443f-ac9f-6d54ff090adc/confirm";
    private static final String PROCESS = "process-445b6066-e657-40ad-9a7e-e87a0e803e54";
    // Lunes de 12:00 a 15:00 cada 30 minutos
    private static final String PROFESSIONAL = """
            {"id":201,"firstName":"Ana","lastName":"Gomez","enabled":true,
             "category":{"id":1,"name":"Clinica medica","enabled":true},
             "weeklySchedules":[{"id":21,"dayOfWeek":"MONDAY","startTime":"12:00:00","endTime":"15:00:00",
             "slotDurationMinutes":30,"enabled":true}]}
            """;
    private static final String REGISTER_BODY = """
            {"login":"maria.gomez","password":"secreta123","firstName":"María","lastName":"Gómez",
             "email":"maria.gomez@example.com","langKey":"es"}
            """;
    private static final String CREDENTIALS = "{\"username\":\"maria.gomez\",\"password\":\"secreta123\"}";
    // El próximo lunes, nunca hoy: ningún turno comenzó
    private static final LocalDate MONDAY = LocalDate.now(ZoneId.of("America/Argentina/Mendoza")).with(TemporalAdjusters.next(DayOfWeek.MONDAY));

    // La zona y el margen llegan por las mismas variables que usa el .env
    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("TURNOS_CATALOG_BASE_URL", CATALOG::baseUrl);
        registry.add("TURNOS_CATALOG_INITIAL_DELAY", () -> "1ms");
        registry.add("TURNOS_CATEDRA_OPERATIONS_INITIAL_DELAY", () -> "1ms");
        registry.add("TURNOS_ATTENTION_ZONE", () -> "America/Argentina/Cordoba");
        registry.add("TURNOS_RESERVATION_SAFETY_MARGIN", () -> "45s");
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "cuenta-tecnica-de-test");
        registry.add("turnos.catedra.password", () -> "clave-de-test");
    }

    @AfterAll
    static void stopServers() {
        CATALOG.close();
        CATEDRA.close();
    }

    @Autowired
    private RestTestClient restTestClient;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TechnicalSessionRepository technicalSessionRepository;
    @Autowired
    private ReservationPolicy reservationPolicy;
    @Autowired
    private AvailabilityPolicy availabilityPolicy;

    private String userToken;
    private Long userId;
    // Confirmar no cambia el vencimiento: el hold y la confirmación traen el mismo
    private Instant holdExpiresAt;

    // Los procesos se borran antes que los usuarios: la base no deja borrar un usuario con procesos
    @BeforeEach
    void registerAndLogIn() {
        await().atMost(Duration.ofSeconds(10)).until(() -> technicalSessionRepository.get().isEnabled());
        cleanDatabase();
        restTestClient.post().uri("/api/register").contentType(MediaType.APPLICATION_JSON).body(REGISTER_BODY)
                .exchange().expectStatus().isCreated();
        String body = restTestClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON).body(CREDENTIALS)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        userToken = JsonPath.read(body, "$.id_token");
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE login = 'maria.gomez'", Long.class);
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(200, PROFESSIONAL));
        holdExpiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        CATEDRA.http().respond(HOLDS_PATH, FakeHttpServer.Response.of(201, grantedHold()));
        CATEDRA.http().respond(CONFIRM_PATH, FakeHttpServer.Response.of(202, acceptedConfirmation()));
    }

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM reservation_processes");
        jdbcTemplate.update("DELETE FROM user_authorities");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void aLoggedInUserBlocksASlotAndTheProcessIsSavedAsHers() {
        int catalogBefore = CATALOG.receivedRequests().size();
        int holdsBefore = CATEDRA.receivedRequests().stream().filter(r -> r.path().equals(HOLDS_PATH)).toList().size();

        String body = block(userToken, "12:30").expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.reservationProcessId")).isEqualTo("process-445b6066-e657-40ad-9a7e-e87a0e803e54");
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("HELD");
        assertThat(JsonPath.<String>read(body, "$.startTime")).isEqualTo("12:30");
        assertThat(JsonPath.<String>read(body, "$.professionalCategoryName")).isEqualTo("Clinica medica");
        assertThat(body).doesNotContain("hold-fbeebd61");

        FakeHttpServer.ReceivedRequest catalogRequest = CATALOG.receivedRequests().get(catalogBefore);
        assertThat(catalogRequest.path()).isEqualTo("/api/professionals/201");
        assertThat(catalogRequest.header("Authorization")).isEqualTo("Bearer " + userToken);
        FakeHttpServer.ReceivedRequest holdRequest = CATEDRA.receivedRequests().stream()
                .filter(r -> r.path().equals(HOLDS_PATH)).toList().get(holdsBefore);
        assertThat(holdRequest.header("Authorization")).isEqualTo("Bearer " + FakeCatedraServer.TOKEN);
        assertThat(JsonMapper.builder().build().readValue(holdRequest.body(), Map.class)).isEqualTo(Map.of(
                "professionalId", 201, "date", MONDAY.toString(), "startTime", "12:30", "externalPatientId", userId.toString()));

        Map<String, Object> saved = jdbcTemplate.queryForMap("SELECT * FROM reservation_processes");
        assertThat(saved).containsEntry("user_id", userId).containsEntry("hold_id", "hold-fbeebd61-9202-443f-ac9f-6d54ff090adc")
                .containsEntry("status", "HELD").containsEntry("professional_first_name", "Ana");
    }

    @Test
    void aSecondBlockOfTheSameUserAnswersWithTheOpenProcess() {
        block(userToken, "12:30").expectStatus().isCreated();

        String body = block(userToken, "13:00").expectStatus().isEqualTo(409).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("OPEN_PROCESS_EXISTS");
        assertThat(JsonPath.<String>read(body, "$.reservationProcessId")).isEqualTo("process-445b6066-e657-40ad-9a7e-e87a0e803e54");
        assertThat(JsonPath.<String>read(body, "$.currentStatus")).isEqualTo("HELD");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reservation_processes", Long.class)).isEqualTo(1);
    }

    // Solo cuenta si el profesional está habilitado: una categoría deshabilitada no impide bloquear
    @Test
    void aProfessionalOfADisabledCategoryCanBeBlocked() {
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(200,
                PROFESSIONAL.replace("\"name\":\"Clinica medica\",\"enabled\":true", "\"name\":\"Clinica medica\",\"enabled\":false")));

        String body = block(userToken, "12:30").expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("HELD");
        assertThat(JsonPath.<String>read(body, "$.professionalCategoryName")).isEqualTo("Clinica medica");
    }

    @Test
    void aTokenOfAUserThatDoesNotExistIsRejectedWithoutCallingOutside() {
        int catalogBefore = CATALOG.receivedRequests().size();
        int catedraBefore = CATEDRA.receivedRequests().size();

        block(TestTokens.forUser("999999", "ROLE_USER"), "12:30").expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

        assertThat(CATALOG.receivedRequests()).hasSize(catalogBefore);
        assertThat(CATEDRA.receivedRequests()).hasSize(catedraBefore);
    }

    @Test
    void anUnavailableCatalogNeverReachesTheCatedra() {
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(503, "{\"status\":503,\"code\":\"CATALOG_NOT_INITIALIZED\"}"));
        int catalogBefore = CATALOG.receivedRequests().size();
        long holdsBefore = holdRequests();

        String body = block(userToken, "12:30").expectStatus().isEqualTo(503).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("CATALOG_UNAVAILABLE");
        assertThat(CATALOG.receivedRequests()).hasSize(catalogBefore + 3);
        assertThat(holdRequests()).isEqualTo(holdsBefore);
    }

    @Test
    void aCatedraErrorThatMayHaveCreatedTheHoldIsNotRetried(CapturedOutput output) {
        CATEDRA.http().respond(HOLDS_PATH, FakeHttpServer.Response.of(500, "{}"));
        long holdsBefore = holdRequests();

        String body = block(userToken, "12:30").expectStatus().isEqualTo(503).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("CENTRAL_UNAVAILABLE");
        assertThat(holdRequests()).isEqualTo(holdsBefore + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reservation_processes", Long.class)).isZero();
        assertThat(output.getAll()).contains("may have created a hold").doesNotContain(userToken)
                .doesNotContain(FakeCatedraServer.TOKEN);
    }

    @Test
    void aLoggedInUserConfirmsTheBlockedSlotWithTheNameOfHerProfile() {
        String blocked = block(userToken, "12:30").expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        long confirmationsBefore = confirmRequests();

        String body = confirm(userToken, PROCESS).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.reservationProcessId")).isEqualTo(PROCESS);
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("WAITING_FOR_PHONE");
        assertThat(JsonPath.<String>read(body, "$.expiresAt")).isEqualTo(JsonPath.<String>read(blocked, "$.expiresAt"));
        assertThat(body).doesNotContain("hold-fbeebd61").doesNotContain("econciliation");

        FakeHttpServer.ReceivedRequest request = CATEDRA.receivedRequests().stream()
                .filter(r -> r.path().equals(CONFIRM_PATH)).toList().get((int) confirmationsBefore);
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + FakeCatedraServer.TOKEN);
        assertThat(JsonMapper.builder().build().readValue(request.body(), Map.class)).isEqualTo(Map.of(
                "externalPatientId", userId.toString(), "patientFirstName", "María", "patientLastName", "Gómez"));
        Map<String, Object> saved = jdbcTemplate.queryForMap("SELECT * FROM reservation_processes");
        assertThat(saved).containsEntry("status", "WAITING_FOR_PHONE").containsEntry("needs_reconciliation", false);
    }

    @Test
    void aSecondConfirmationAnswersWithTheCurrentStatusWithoutCallingTheCatedra() {
        block(userToken, "12:30").expectStatus().isCreated();
        confirm(userToken, PROCESS).expectStatus().isOk();
        long confirmationsBefore = confirmRequests();

        String body = confirm(userToken, PROCESS).expectStatus().isEqualTo(409).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("INVALID_PROCESS_STATE");
        assertThat(JsonPath.<String>read(body, "$.currentStatus")).isEqualTo("WAITING_FOR_PHONE");
        assertThat(confirmRequests()).isEqualTo(confirmationsBefore);
    }

    @Test
    void theTokenOfAUserThatDoesNotExistFindsNoProcessToConfirm() {
        block(userToken, "12:30").expectStatus().isCreated();
        long confirmationsBefore = confirmRequests();

        String body = confirm(TestTokens.forUser("999999", "ROLE_USER"), PROCESS).expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("RESERVATION_PROCESS_NOT_FOUND");
        assertThat(confirmRequests()).isEqualTo(confirmationsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reservation_processes", String.class)).isEqualTo("HELD");
    }

    @Test
    void aCatedraThatDoesNotAnswerTheConfirmationLeavesTheProcessHeldAndMarked(CapturedOutput output) {
        block(userToken, "12:30").expectStatus().isCreated();
        CATEDRA.http().respond(CONFIRM_PATH, FakeHttpServer.Response.of(500, "{}"));
        long confirmationsBefore = confirmRequests();

        String body = confirm(userToken, PROCESS).expectStatus().isEqualTo(503).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("CENTRAL_UNAVAILABLE");
        assertThat(confirmRequests()).isEqualTo(confirmationsBefore + 3);
        Map<String, Object> saved = jdbcTemplate.queryForMap("SELECT * FROM reservation_processes");
        assertThat(saved).containsEntry("status", "HELD").containsEntry("needs_reconciliation", true);
        assertThat(output.getAll()).contains("confirmation of hold-fbeebd61-9202-443f-ac9f-6d54ff090adc attempt 3/3 failed")
                .doesNotContain(userToken).doesNotContain(FakeCatedraServer.TOKEN);
    }

    @Test
    void theZoneAndTheMarginComeFromTheEnvironmentAndTheZoneIsSharedWithAvailability() {
        assertThat(reservationPolicy.zone()).isEqualTo(ZoneId.of("America/Argentina/Cordoba"));
        assertThat(availabilityPolicy.zone()).isEqualTo(reservationPolicy.zone());
        assertThat(reservationPolicy.safetyMargin()).isEqualTo(Duration.ofSeconds(45));
    }

    private RestTestClient.ResponseSpec block(String token, String startTime) {
        return restTestClient.post().uri("/api/reservation-processes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"professionalId\":201,\"date\":\"" + MONDAY + "\",\"startTime\":\"" + startTime + "\"}")
                .exchange();
    }

    private long holdRequests() {
        return CATEDRA.receivedRequests().stream().filter(r -> r.path().equals(HOLDS_PATH)).count();
    }

    // La respuesta real grabada, con un vencimiento dentro de diez minutos para que el proceso siga abierto
    private String grantedHold() {
        return recording("hold-201-created.json").replace("2026-10-27T04:44:38.627744064Z", holdExpiresAt.toString());
    }

    // La confirmación real grabada, ajustada al proceso y al vencimiento del hold de este test
    private String acceptedConfirmation() {
        return recording("confirm-202-accepted.json")
                .replace("2026-10-28T05:04:54.812004682Z", holdExpiresAt.toString())
                .replace("process-bfcb30eb-6e39-402f-bd92-95e73bfec54d", PROCESS);
    }

    private RestTestClient.ResponseSpec confirm(String token, String reservationProcessId) {
        return restTestClient.post().uri("/api/reservation-processes/{id}/confirm", reservationProcessId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private long confirmRequests() {
        return CATEDRA.receivedRequests().stream().filter(r -> r.path().equals(CONFIRM_PATH)).count();
    }

    private static String recording(String name) {
        try (InputStream input = ReservationProcessIntegrationTest.class.getResourceAsStream("/catedra/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
