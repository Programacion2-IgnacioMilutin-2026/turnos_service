package turnos.reservation;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import turnos.FakeHttpServer;
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa en un puerto real, con Postgres real y catálogo y cátedra simulados. El proceso se escribe
// en la base como estado de partida: lo que se prueba es la consulta, no cómo llegó a ese estado
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, ControllableProcessRepository.Configuration.class})
@ExtendWith(OutputCaptureExtension.class)
class ReservationProcessStatusIntegrationTest {

    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();
    private static final String PROCESS = "process-1";
    private static final String PHONE = "+5492615551234";
    private static final Duration MARGIN = Duration.ofSeconds(60);
    private static final LocalDate DATE = LocalDate.of(2026, 11, 9);

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("TURNOS_CATALOG_BASE_URL", CATALOG::baseUrl);
        registry.add("TURNOS_RESERVATION_SAFETY_MARGIN", () -> MARGIN.getSeconds() + "s");
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
    @Autowired
    private UserOperationLock userOperationLock;
    @Value("${local.server.port}")
    private int port;

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
    }

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM reservation_processes");
        jdbcTemplate.update("DELETE FROM user_authorities");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void anUnknownProcessAndOneOfAnotherUserAnswerTheSameEvenIfItIsOverdue() {
        insert(PROCESS, otherUserId, ReservationStatus.WAITING_FOR_PHONE, overdue());
        Map<String, Object> before = stored(PROCESS);

        Map<String, Object> foreign = JsonPath.read(notFound(PROCESS), "$");
        Map<String, Object> unknown = JsonPath.read(notFound("does-not-exist"), "$");

        assertThat(foreign).containsEntry("code", "RESERVATION_PROCESS_NOT_FOUND");
        assertThat(foreign.get("detail")).isEqualTo(unknown.get("detail"));
        assertThat(foreign.get("title")).isEqualTo(unknown.get("title"));
        assertThat(foreign.keySet()).isEqualTo(unknown.keySet());
        assertThat(stored(PROCESS)).isEqualTo(before);
        assertThat(database.updates()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void anOverdueProcessWaitingForTheUserIsClosedBeforeResponding(ReservationStatus status) {
        insert(PROCESS, userId, status, overdue());
        jdbcTemplate.update("UPDATE reservation_processes SET rejection_reason = 'INVALID_PHONE_NUMBER', needs_reconciliation = TRUE");

        String body = found();

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("EXPIRED");
        assertThat(JsonPath.<Object>read(body, "$.expiresAt")).isNull();
        assertThat(JsonPath.<Object>read(body, "$.rejectionReason")).isNull();
        Map<String, Object> stored = stored(PROCESS);
        assertThat(stored).containsEntry("status", "EXPIRED").containsEntry("needs_reconciliation", false)
                .containsEntry("rejection_reason", null);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void insideTheMarginNothingChanges(ReservationStatus status) {
        insert(PROCESS, userId, status, Instant.now().minusSeconds(5));
        Map<String, Object> before = stored(PROCESS);

        String body = found();

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo(status.name());
        assertThat(JsonPath.<Object>read(body, "$.expiresAt")).isNotNull();
        assertThat(stored(PROCESS)).isEqualTo(before);
        assertThat(database.updates()).isZero();
    }

    @Test
    void anOverdueProcessWithThePhoneSubmittedIsMarkedOnceAndKeepsItsData() {
        insert(PROCESS, userId, ReservationStatus.PHONE_SUBMITTED, overdue());
        jdbcTemplate.update("UPDATE reservation_processes SET phone_number = ?, request_event_id = 'request-event-1'", PHONE);

        String first = found();
        String second = found();

        assertThat(JsonPath.<String>read(first, "$.status")).isEqualTo("PHONE_SUBMITTED");
        assertThat(JsonPath.<Object>read(first, "$.expiresAt")).isNotNull();
        assertThat(second).isEqualTo(first);
        assertThat(stored(PROCESS)).containsEntry("status", "PHONE_SUBMITTED").containsEntry("needs_reconciliation", true)
                .containsEntry("phone_number", PHONE).containsEntry("request_event_id", "request-event-1");
        assertThat(database.updates()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CANCELLED", "EXPIRED", "INVALID"})
    void aClosedProcessDoesNotChangeAndNothingIsCalled(ReservationStatus status) {
        insert(PROCESS, userId, status, overdue());
        Map<String, Object> before = stored(PROCESS);
        int catedraRequests = CATEDRA.receivedRequests().size();
        int catalogRequests = CATALOG.receivedRequests().size();

        String body = found();

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo(status.name());
        assertThat(stored(PROCESS)).isEqualTo(before);
        assertThat(database.updates()).isZero();
        assertThat(CATEDRA.receivedRequests()).hasSize(catedraRequests);
        assertThat(CATALOG.receivedRequests()).hasSize(catalogRequests);
    }

    @ParameterizedTest
    @EnumSource(ReservationStatus.class)
    void noStatusExposesTheHoldTheMarkThePhoneRequestOrThePhone(ReservationStatus status, CapturedOutput output) {
        insert(PROCESS, userId, status, overdue());
        jdbcTemplate.update("UPDATE reservation_processes SET phone_number = ?, request_event_id = 'request-event-1'", PHONE);

        String body = found();

        Map<String, Object> fields = JsonPath.read(body, "$");
        assertThat(fields).containsOnlyKeys("reservationProcessId", "status", "professionalId", "professionalFirstName",
                "professionalLastName", "professionalCategoryName", "date", "startTime", "expiresAt", "rejectionReason",
                "reservationId", "confirmedAt", "cancelledAt", "cancellationReason");
        assertThat(body).doesNotContain("hold-").doesNotContain("request-event-1").doesNotContain("5492615551234");
        assertThat(output.getAll()).doesNotContain("5492615551234");
    }

    @Test
    void anOpenProcessAnswersItsDataAsItWasSaved() {
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        insert(PROCESS, userId, ReservationStatus.HELD, expiresAt);

        String body = found();

        Map<String, Object> fields = JsonPath.read(body, "$");
        assertThat(fields).containsEntry("reservationProcessId", PROCESS).containsEntry("status", "HELD")
                .containsEntry("professionalId", 201).containsEntry("professionalFirstName", "Ana")
                .containsEntry("professionalLastName", "Gomez").containsEntry("professionalCategoryName", "Clinica medica")
                .containsEntry("date", "2026-11-09").containsEntry("startTime", "10:30")
                .containsEntry("expiresAt", expiresAt.truncatedTo(ChronoUnit.SECONDS).toString())
                .containsEntry("rejectionReason", null).containsEntry("reservationId", null)
                .containsEntry("confirmedAt", null).containsEntry("cancelledAt", null)
                .containsEntry("cancellationReason", null);
    }

    @Test
    void aRejectedPhoneAnswersItsReasonAndItsDeadline() {
        insert(PROCESS, userId, ReservationStatus.WAITING_FOR_PHONE, Instant.now().plus(10, ChronoUnit.MINUTES));
        jdbcTemplate.update("UPDATE reservation_processes SET rejection_reason = 'INVALID_PHONE_NUMBER'");

        String body = found();

        assertThat(JsonPath.<String>read(body, "$.rejectionReason")).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(JsonPath.<Object>read(body, "$.expiresAt")).isNotNull();
    }

    @Test
    void aConfirmedReservationAnswersItsIdAndWhenInSeconds() {
        insert(PROCESS, userId, ReservationStatus.CONFIRMED, overdue());
        jdbcTemplate.update("UPDATE reservation_processes SET reservation_id = 8251, confirmed_at = ?",
                Timestamp.from(Instant.parse("2026-10-27T04:36:01.987654Z")));

        Map<String, Object> fields = JsonPath.read(found(), "$");

        assertThat(fields).containsEntry("reservationId", 8251).containsEntry("confirmedAt", "2026-10-27T04:36:01Z")
                .containsEntry("expiresAt", null).containsEntry("cancelledAt", null);
    }

    @Test
    void aReservationCancelledAfterConfirmationKeepsItsIdAndAnswersTheCancellation() {
        insert(PROCESS, userId, ReservationStatus.CANCELLED, overdue());
        jdbcTemplate.update("UPDATE reservation_processes SET reservation_id = 8251, confirmed_at = ?, cancelled_at = ?, "
                        + "cancellation_reason = 'Cambio solicitado por el usuario'",
                Timestamp.from(Instant.parse("2026-10-27T04:36:01Z")), Timestamp.from(Instant.parse("2026-10-28T15:10:00.5Z")));

        Map<String, Object> fields = JsonPath.read(found(), "$");

        assertThat(fields).containsEntry("reservationId", 8251).containsEntry("confirmedAt", "2026-10-27T04:36:01Z")
                .containsEntry("cancelledAt", "2026-10-28T15:10:00Z")
                .containsEntry("cancellationReason", "Cambio solicitado por el usuario").containsEntry("expiresAt", null);
    }

    @Test
    void aProcessCancelledBeforeConfirmationHasNoReservation() {
        insert(PROCESS, userId, ReservationStatus.CANCELLED, overdue());
        jdbcTemplate.update("UPDATE reservation_processes SET cancelled_at = ?", Timestamp.from(Instant.parse("2026-10-28T15:10:00Z")));

        Map<String, Object> fields = JsonPath.read(found(), "$");

        assertThat(fields).containsEntry("cancelledAt", "2026-10-28T15:10:00Z").containsEntry("reservationId", null)
                .containsEntry("confirmedAt", null).containsEntry("cancellationReason", null);
    }

    @Test
    void aReservationConfirmedWhileTheQueryWaitsForTheLockIsNotOverwritten() throws InterruptedException {
        insert(PROCESS, userId, ReservationStatus.WAITING_FOR_PHONE, overdue());
        CountDownLatch firstRead = new CountDownLatch(1);
        database.afterNextOwnerRead(firstRead::countDown);
        AtomicReference<String> answer = new AtomicReference<>();

        Thread query = userOperationLock.exclusivelyFor(userId, () -> {
            Thread started = Thread.ofVirtual().start(() -> answer.set(found()));
            awaitLatch(firstRead);
            jdbcTemplate.update("UPDATE reservation_processes SET status = 'CONFIRMED', reservation_id = 8251");
            return started;
        });
        assertThat(query.join(Duration.ofSeconds(10))).isTrue();

        assertThat(JsonPath.<String>read(answer.get(), "$.status")).isEqualTo("CONFIRMED");
        assertThat(JsonPath.<Integer>read(answer.get(), "$.reservationId")).isEqualTo(8251);
        assertThat(stored(PROCESS)).containsEntry("status", "CONFIRMED").containsEntry("needs_reconciliation", false);
        assertThat(database.updates()).isZero();
    }

    @Test
    void whenClosingFailsTheProcessStaysAsItWasAndTheNextQueryClosesIt(CapturedOutput output) {
        insert(PROCESS, userId, ReservationStatus.WAITING_FOR_PHONE, overdue());
        jdbcTemplate.update("UPDATE reservation_processes SET phone_number = ?", PHONE);
        Map<String, Object> before = stored(PROCESS);
        database.failUpdatesWith("could not write the row with " + PHONE);

        expectInternalError(PROCESS);

        assertThat(stored(PROCESS)).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("5492615551234");

        database.reset();
        assertThat(JsonPath.<String>read(found(), "$.status")).isEqualTo("EXPIRED");
        assertThat(stored(PROCESS)).containsEntry("status", "EXPIRED");
    }

    @Test
    void whenMarkingFailsTheProcessStaysAsItWasAndTheNextQueryMarksIt(CapturedOutput output) {
        insert(PROCESS, userId, ReservationStatus.PHONE_SUBMITTED, overdue());
        jdbcTemplate.update("UPDATE reservation_processes SET phone_number = ?", PHONE);
        Map<String, Object> before = stored(PROCESS);
        database.failUpdatesWith("could not write the row with " + PHONE);

        expectInternalError(PROCESS);

        assertThat(stored(PROCESS)).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("5492615551234");

        database.reset();
        assertThat(JsonPath.<String>read(found(), "$.status")).isEqualTo("PHONE_SUBMITTED");
        assertThat(stored(PROCESS)).containsEntry("needs_reconciliation", true);
    }

    @Test
    void anIdWithTheNullCharacterIsNeverAnInternalError() throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/reservation-processes/process%001"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void aVeryLongIdIsJustAnUnknownProcess() {
        assertThat(JsonPath.<String>read(notFound("x".repeat(300)), "$.code")).isEqualTo("RESERVATION_PROCESS_NOT_FOUND");
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

    private void insert(String reservationProcessId, Long owner, ReservationStatus status, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    professional_first_name, professional_last_name, professional_category_name, appointment_date,
                    start_time, status, expires_at)
                VALUES (?, ?, ?, 201, 'Ana', 'Gomez', 'Clinica medica', ?, ?, ?, ?)
                """, reservationProcessId, "hold-" + reservationProcessId, owner, Date.valueOf(DATE),
                Time.valueOf(LocalTime.of(10, 30)), status.name(), Timestamp.from(expiresAt));
    }

    private Map<String, Object> stored(String reservationProcessId) {
        return jdbcTemplate.queryForMap("SELECT * FROM reservation_processes WHERE reservation_process_id = ?", reservationProcessId);
    }

    private RestTestClient.ResponseSpec query(String reservationProcessId) {
        return restTestClient.get().uri("/api/reservation-processes/{id}", reservationProcessId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private String found() {
        return query(PROCESS).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    private String notFound(String reservationProcessId) {
        return query(reservationProcessId).expectStatus().isNotFound().expectBody(String.class).returnResult().getResponseBody();
    }

    private void expectInternalError(String reservationProcessId) {
        String body = query(reservationProcessId).expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("INTERNAL_ERROR");
        assertThat(body).doesNotContain("could not write");
    }

    private static Instant overdue() {
        return Instant.now().minus(MARGIN).minus(10, ChronoUnit.MINUTES);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
