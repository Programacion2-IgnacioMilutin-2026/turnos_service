package turnos.reservation;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;
import turnos.reservation.domain.model.ReservationStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa en un puerto real, con Postgres real y catálogo y cátedra simulados. Los procesos se escriben
// en la base como estado de partida, salvo el que se bloquea después de abandonar otro
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, ControllableProcessRepository.Configuration.class})
@ExtendWith(OutputCaptureExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReservationCancellationIntegrationTest {

    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROCESS = "process-1";
    private static final String CANCEL_PATH = "/api/appointments/" + PROCESS + "/cancel";
    private static final String HOLDS_PATH = "/api/appointment-holds";
    private static final String PHONE = "+5492615551234";
    private static final String REASON = "Cambio solicitado por el usuario";
    private static final String CANCELLED = """
            {"reservationId":8251,"reservationProcessId":"process-1","status":"CANCELLED","cancelledAt":"2026-11-04T15:10:00.5Z"}
            """;
    private static final String PROFESSIONAL = """
            {"id":201,"firstName":"Ana","lastName":"Gomez","enabled":true,
             "category":{"id":1,"name":"Clinica medica","enabled":true},
             "weeklySchedules":[{"id":21,"dayOfWeek":"MONDAY","startTime":"12:00:00","endTime":"15:00:00",
             "slotDurationMinutes":30,"enabled":true}]}
            """;
    private static final LocalDate MONDAY = LocalDate.now(ZoneId.of("America/Argentina/Mendoza")).with(TemporalAdjusters.next(DayOfWeek.MONDAY));

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("TURNOS_CATALOG_BASE_URL", CATALOG::baseUrl);
        registry.add("TURNOS_CATALOG_INITIAL_DELAY", () -> "1ms");
        registry.add("TURNOS_CATEDRA_OPERATIONS_INITIAL_DELAY", () -> "1ms");
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
    private ControllableProcessRepository database;

    private String token;
    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void registerAndLogIn() {
        await().atMost(Duration.ofSeconds(10)).until(() -> technicalSessionRepository.get().isEnabled());
        cleanDatabase();
        database.reset();
        token = registerAndAuthenticate("maria.gomez");
        registerAndAuthenticate("pedro.perez");
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE login = 'maria.gomez'", Long.class);
        otherUserId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE login = 'pedro.perez'", Long.class);
        CATEDRA.http().respond(CANCEL_PATH, FakeHttpServer.Response.of(200, CANCELLED));
    }

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM reservation_processes");
        jdbcTemplate.update("DELETE FROM user_authorities");
        jdbcTemplate.update("DELETE FROM users");
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void aProcessWaitingForTheUserIsAbandonedWithoutCallingTheCatedra(ReservationStatus status) {
        insert(PROCESS, userId, status, future());
        int requests = cancelRequests();

        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String body = cancelOk("{\"reason\":\"" + REASON + "\"}");

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("CANCELLED");
        assertThat(JsonPath.<String>read(body, "$.cancellationReason")).isEqualTo(REASON);
        assertThat(JsonPath.<Object>read(body, "$.expiresAt")).isNull();
        assertThat(Instant.parse(JsonPath.<String>read(body, "$.cancelledAt"))).isAfterOrEqualTo(before);
        assertThat(stored(PROCESS)).containsEntry("status", "CANCELLED").containsEntry("cancellation_reason", REASON)
                .containsEntry("confirmed_at", null).containsEntry("needs_reconciliation", false);
        assertThat(cancelRequests()).isEqualTo(requests);
    }

    @Test
    void anOverdueProcessIsAbandonedToo() {
        insert(PROCESS, userId, ReservationStatus.HELD, Instant.now().minusSeconds(3600));

        assertThat(JsonPath.<String>read(cancelOk(null), "$.status")).isEqualTo("CANCELLED");
        assertThat(stored(PROCESS)).containsEntry("status", "CANCELLED");
    }

    @Test
    void afterAbandoningTheUserCanBlockAnotherSlotAndTheAbandonedOneIsNotListed() {
        insert(PROCESS, userId, ReservationStatus.WAITING_FOR_PHONE, future());
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(200, PROFESSIONAL));
        CATEDRA.http().respond(HOLDS_PATH, FakeHttpServer.Response.of(201, grantedHold()));

        block().expectStatus().isEqualTo(409);
        cancelOk(null);
        String blocked = block().expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();

        String newProcess = JsonPath.read(blocked, "$.reservationProcessId");
        String listed = restTestClient.get().uri("/api/reservation-processes").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonPath.<List<String>>read(listed, "$.items[*].reservationProcessId")).containsExactly(newProcess);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void aMarkedProcessIsNotAbandoned(ReservationStatus status) {
        insert(PROCESS, userId, status, future());
        jdbcTemplate.update("UPDATE reservation_processes SET needs_reconciliation = TRUE");

        expectInvalidState(status);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"PHONE_SUBMITTED", "EXPIRED", "INVALID"})
    void anyOtherStatusIsNotCancellable(ReservationStatus status) {
        insert(PROCESS, userId, status, future());

        expectInvalidState(status);
    }

    @Test
    void aCancelledProcessIsAnsweredAsItIs() {
        insert(PROCESS, userId, ReservationStatus.CANCELLED, future());
        Map<String, Object> before = stored(PROCESS);
        int requests = cancelRequests();

        cancelOk("{\"reason\":\"" + REASON + "\"}");

        assertThat(stored(PROCESS)).isEqualTo(before);
        assertThat(cancelRequests()).isEqualTo(requests);
    }

    @Test
    void anUnknownProcessAndOneOfAnotherUserAnswerTheSameAndNothingChanges() {
        insert("foreign", otherUserId, ReservationStatus.HELD, future());
        Map<String, Object> before = stored("foreign");

        String foreign = cancel("foreign", null).expectStatus().isNotFound().expectBody(String.class).returnResult().getResponseBody();
        String unknown = cancel("does-not-exist", null).expectStatus().isNotFound().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(foreign, "$.code")).isEqualTo("RESERVATION_PROCESS_NOT_FOUND");
        assertThat(JsonPath.<String>read(foreign, "$.detail")).isEqualTo(JsonPath.<String>read(unknown, "$.detail"));
        assertThat(stored("foreign")).isEqualTo(before);
    }

    @Test
    void aConfirmedReservationIsCancelledInTheCatedraWithTheTokenAndTheReason() {
        insertConfirmed();
        int requests = cancelRequests();

        String body = cancelOk("{\"reason\":\"" + REASON + "\"}");

        assertThat(cancelRequests()).isEqualTo(requests + 1);
        FakeHttpServer.ReceivedRequest request = lastCancelRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + FakeCatedraServer.TOKEN);
        assertThat(JSON.readValue(request.body(), Map.class)).isEqualTo(Map.of("reason", REASON));
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("CANCELLED");
        assertThat(JsonPath.<String>read(body, "$.cancelledAt")).isEqualTo("2026-11-04T15:10:00Z");
        assertThat(JsonPath.<Integer>read(body, "$.reservationId")).isEqualTo(8251);
        assertThat(stored(PROCESS)).containsEntry("status", "CANCELLED").containsEntry("cancellation_reason", REASON)
                .containsEntry("reservation_id", 8251L).containsEntry("needs_reconciliation", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}"})
    void withoutAUsefulReasonNothingIsSavedNorSentToTheCatedra(String body) {
        insertConfirmed();

        cancelOk(body);

        assertThat(lastCancelRequest().body()).isEmpty();
        assertThat(stored(PROCESS)).containsEntry("cancellation_reason", null);
    }

    @Test
    void withoutABodyThereIsNoReasonEither() {
        insertConfirmed();

        cancelOk(null);

        assertThat(lastCancelRequest().body()).isEmpty();
    }

    @Test
    void aReasonWithTheNullCharacterIsSavedWithoutIt() {
        insertConfirmed();

        cancelOk("{\"reason\":\"Cam\\u0000bio\"}");

        assertThat(stored(PROCESS)).containsEntry("cancellation_reason", "Cambio");
        assertThat(JSON.readValue(lastCancelRequest().body(), Map.class)).isEqualTo(Map.of("reason", "Cambio"));
    }

    @Test
    void aReasonLongerThanTheLimitIsRejectedAndNothingChanges() {
        insertConfirmed();
        int requests = cancelRequests();

        String body = cancel(PROCESS, "{\"reason\":\"" + "x".repeat(501) + "\"}").expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.fieldErrors[0].field")).isEqualTo("reason");
        assertThat(stored(PROCESS)).containsEntry("status", "CONFIRMED");
        assertThat(cancelRequests()).isEqualTo(requests);
    }

    @Test
    void aReservationTheCatedraDoesNotKnowIsAnInternalErrorAndStaysMarked() {
        expectAnswerAndMark(rejection(404, "RESERVATION_NOT_FOUND"), 500, "INTERNAL_ERROR", true);
    }

    @Test
    void aReservationTheCatedraDoesNotAllowCancellingIsAConflictAndStaysMarked() {
        expectAnswerAndMark(rejection(409, "RESERVATION_NOT_CANCELLABLE"), 409, "RESERVATION_NOT_CANCELLABLE", true);
    }

    @Test
    void aReservationOfAnotherAccountIsAnInternalErrorAndIsNotMarked() {
        expectAnswerAndMark(rejection(403, "RESERVATION_OWNER_MISMATCH"), 500, "INTERNAL_ERROR", false);
    }

    @Test
    void aMissingIntegrationDegradesTheServiceAndIsNotMarked() {
        expectAnswerAndMark(rejection(403, "STUDENT_INTEGRATION_NOT_FOUND"), 503, "SERVICE_DEGRADED", false);
    }

    @Test
    void anUnexpectedAnswerIsAnInternalErrorAndStaysMarked() {
        expectAnswerAndMark(FakeHttpServer.Response.of(200, "{\"status\":\"CONFIRMED\"}"), 500, "INTERNAL_ERROR", true);
    }

    @Test
    void aCatedraThatDoesNotAnswerIsRetriedAndStaysMarked() {
        int requests = cancelRequests();

        expectAnswerAndMark(FakeHttpServer.Response.of(503, "{}"), 503, "CENTRAL_UNAVAILABLE", true);

        assertThat(cancelRequests() - requests).isGreaterThan(1);
    }

    @Test
    void aRetryAfterATemporaryFailureCancels() {
        insertConfirmed();
        CATEDRA.http().respond(CANCEL_PATH, FakeHttpServer.Response.of(503, "{}"), FakeHttpServer.Response.of(200, CANCELLED));

        cancelOk(null);

        assertThat(stored(PROCESS)).containsEntry("status", "CANCELLED").containsEntry("needs_reconciliation", false);
    }

    @Test
    void whenTheCatedraCancelledAndItCannotBeSavedItIsOutOfSyncAndTheNextRequestFixesIt(CapturedOutput output) {
        insertConfirmed();
        jdbcTemplate.update("UPDATE reservation_processes SET phone_number = ?", PHONE);
        database.failUpdatesWith("could not write the row with " + PHONE);

        String body = cancel(PROCESS, null).expectStatus().isEqualTo(500).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("LOCAL_STATE_OUT_OF_SYNC");
        assertThat(stored(PROCESS)).containsEntry("status", "CONFIRMED").containsEntry("needs_reconciliation", true);
        assertThat(output.getAll()).doesNotContain("5492615551234");

        database.reset();
        cancelOk(null);
        assertThat(stored(PROCESS)).containsEntry("status", "CANCELLED").containsEntry("needs_reconciliation", false);
    }

    @Test
    void whenAnAbandonCannotBeSavedNothingChanges(CapturedOutput output) {
        insert(PROCESS, userId, ReservationStatus.WAITING_FOR_PHONE, future());
        jdbcTemplate.update("UPDATE reservation_processes SET phone_number = ?", PHONE);
        Map<String, Object> before = stored(PROCESS);
        database.failUpdatesWith("could not write the row with " + PHONE);

        String body = cancel(PROCESS, null).expectStatus().isEqualTo(500).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("INTERNAL_ERROR");
        assertThat(stored(PROCESS)).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("5492615551234");
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "CONFIRMED", "CANCELLED"})
    void noAnswerExposesTheHoldTheMarkThePhoneRequestOrThePhone(ReservationStatus status, CapturedOutput output) {
        insert(PROCESS, userId, status, future());
        jdbcTemplate.update("UPDATE reservation_processes SET phone_number = ?, request_event_id = 'request-event-1'", PHONE);

        String body = cancelOk(null);

        Map<String, Object> fields = JsonPath.read(body, "$");
        assertThat(fields).containsOnlyKeys("reservationProcessId", "status", "professionalId", "professionalFirstName",
                "professionalLastName", "professionalCategoryName", "date", "startTime", "expiresAt", "rejectionReason",
                "reservationId", "confirmedAt", "cancelledAt", "cancellationReason");
        assertThat(body).doesNotContain("hold-").doesNotContain("request-event-1").doesNotContain("5492615551234");
        assertThat(output.getAll()).doesNotContain("5492615551234");
    }

    private void expectInvalidState(ReservationStatus status) {
        Map<String, Object> before = stored(PROCESS);
        int requests = cancelRequests();

        String body = cancel(PROCESS, null).expectStatus().isEqualTo(409).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("INVALID_PROCESS_STATE");
        assertThat(JsonPath.<String>read(body, "$.currentStatus")).isEqualTo(status.name());
        assertThat(stored(PROCESS)).isEqualTo(before);
        assertThat(cancelRequests()).isEqualTo(requests);
    }

    private void expectAnswerAndMark(FakeHttpServer.Response catedraAnswer, int status, String code, boolean marked) {
        insertConfirmed();
        CATEDRA.http().respond(CANCEL_PATH, catedraAnswer);

        String body = cancel(PROCESS, null).expectStatus().isEqualTo(status).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo(code);
        assertThat(stored(PROCESS)).containsEntry("status", "CONFIRMED").containsEntry("needs_reconciliation", marked);
    }

    private static FakeHttpServer.Response rejection(int status, String code) {
        return FakeHttpServer.Response.of(status, """
                {"title":"Rejected","status":%d,"code":"%s"}
                """.formatted(status, code)).withContentType("application/problem+json");
    }

    private String registerAndAuthenticate(String login) {
        restTestClient.post().uri("/api/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"login":"%s","password":"secreta123","firstName":"María","lastName":"Gómez",
                         "email":"%s@example.com","langKey":"es"}
                        """.formatted(login, login))
                .exchange().expectStatus().isCreated();
        String body = restTestClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                .body("{\"username\":\"%s\",\"password\":\"secreta123\"}".formatted(login))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        return JsonPath.read(body, "$.id_token");
    }

    private void insertConfirmed() {
        insert(PROCESS, userId, ReservationStatus.CONFIRMED, future());
        jdbcTemplate.update("UPDATE reservation_processes SET reservation_id = 8251, confirmed_at = ?",
                Timestamp.from(Instant.parse("2026-10-27T04:36:01Z")));
    }

    private void insert(String reservationProcessId, Long owner, ReservationStatus status, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    professional_first_name, professional_last_name, professional_category_name, appointment_date,
                    start_time, status, expires_at)
                VALUES (?, ?, ?, 201, 'Ana', 'Gomez', 'Clinica medica', ?, ?, ?, ?)
                """, reservationProcessId, "hold-" + reservationProcessId, owner, Date.valueOf(MONDAY),
                Time.valueOf(LocalTime.of(12, 30)), status.name(), Timestamp.from(expiresAt));
    }

    private Map<String, Object> stored(String reservationProcessId) {
        return jdbcTemplate.queryForMap("SELECT * FROM reservation_processes WHERE reservation_process_id = ?", reservationProcessId);
    }

    private String cancelOk(String body) {
        return cancel(PROCESS, body).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    private RestTestClient.ResponseSpec cancel(String reservationProcessId, String body) {
        RestTestClient.RequestBodySpec request = restTestClient.post()
                .uri("/api/reservation-processes/{id}/cancel", reservationProcessId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return body == null ? request.exchange() : request.contentType(MediaType.APPLICATION_JSON).body(body).exchange();
    }

    private RestTestClient.ResponseSpec block() {
        return restTestClient.post().uri("/api/reservation-processes").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"professionalId\":201,\"date\":\"" + MONDAY + "\",\"startTime\":\"12:00\"}")
                .exchange();
    }

    private int cancelRequests() {
        return CATEDRA.http().receivedRequests(CANCEL_PATH).size();
    }

    private FakeHttpServer.ReceivedRequest lastCancelRequest() {
        return CATEDRA.http().receivedRequests(CANCEL_PATH).getLast();
    }

    private static Instant future() {
        return Instant.now().plus(10, ChronoUnit.MINUTES);
    }

    private static String grantedHold() {
        try (InputStream input = ReservationCancellationIntegrationTest.class.getResourceAsStream("/catedra/hold-201-created.json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("2026-10-27T04:44:38.627744064Z", future().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
