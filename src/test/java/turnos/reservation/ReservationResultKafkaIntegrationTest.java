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
import tools.jackson.databind.node.ObjectNode;
import turnos.FakeHttpServer;
import turnos.KafkaTestContainer;
import turnos.KafkaTestTopic;
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;
import turnos.reservation.PhoneRequestKafkaIntegrationTest.ControllableTransactionRunner;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa con Postgres y Kafka reales (Testcontainers) y la cátedra REST y el catálogo simulados.
// El test publica en el topic de acciones los resultados que publicaría la cátedra, armados desde sus ejemplos
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, PhoneRequestKafkaIntegrationTest.ControllableDatabase.class})
@ExtendWith(OutputCaptureExtension.class)
class ReservationResultKafkaIntegrationTest {

    private static final KafkaTestTopic ACTIONS = KafkaTestTopic.random("results");
    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HOLDS_PATH = "/api/appointment-holds";
    // El hold y el proceso de la grabación del hold
    private static final String HOLD = "hold-fbeebd61-9202-443f-ac9f-6d54ff090adc";
    private static final String CONFIRM_PATH = HOLDS_PATH + "/" + HOLD + "/confirm";
    private static final String PROCESS = "process-445b6066-e657-40ad-9a7e-e87a0e803e54";
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
    private static final LocalDate MONDAY = LocalDate.now(ZoneId.of("America/Argentina/Mendoza")).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-12T14:36:01Z");

    static {
        CATEDRA.respond(200, FakeCatedraServer.provisionedLoginResponse("PROVISIONED",
                KafkaTestContainer.bootstrapServers(), ACTIONS.topic(), ACTIONS.consumerGroup()));
    }

    // Esperas cortas para que el test sea rápido; las reglas son las de la configuración real
    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("TURNOS_CATALOG_BASE_URL", CATALOG::baseUrl);
        registry.add("TURNOS_CATALOG_INITIAL_DELAY", () -> "1ms");
        registry.add("TURNOS_CATEDRA_OPERATIONS_INITIAL_DELAY", () -> "1ms");
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "cuenta-tecnica-de-test");
        registry.add("turnos.catedra.password", () -> "clave-de-test");
        registry.add("turnos.catedra.kafka.enabled", () -> "true");
        registry.add("turnos.catedra.kafka.retry.initial-delay", () -> "10ms");
        registry.add("turnos.catedra.kafka.retry.max-attempts", () -> "2");
        registry.add("turnos.catedra.kafka.retry.pause", () -> "200ms");
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
    private ControllableTransactionRunner database;

    private String userToken;
    private Long userId;
    private Instant expiresAt;

    @BeforeEach
    void registerAndLogIn() {
        await().atMost(Duration.ofSeconds(10)).until(() -> technicalSessionRepository.get().isEnabled());
        cleanDatabase();
        database.reset();
        restTestClient.post().uri("/api/register").contentType(MediaType.APPLICATION_JSON).body(REGISTER_BODY)
                .exchange().expectStatus().isCreated();
        String body = restTestClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON).body(CREDENTIALS)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        userToken = JsonPath.read(body, "$.id_token");
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE login = 'maria.gomez'", Long.class);
        expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    }

    // Los procesos se borran antes que los usuarios: la base no deja borrar un usuario con procesos
    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM processed_events");
        jdbcTemplate.update("DELETE FROM reservation_processes");
        jdbcTemplate.update("DELETE FROM user_authorities");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void aConfirmationLeavesTheReservationInTheDatabaseWithTheDataOfTheCatedraAndTurnsTheMarkOff(CapturedOutput output) {
        insertProcess("process-1", userId, "hold-1", "PHONE_SUBMITTED", true);
        String eventId = newEventId();

        ACTIONS.publish("process-1", result("appointment-confirmed.json", eventId, "process-1", "hold-1"));

        awaitProcessed(eventId);
        Map<String, Object> stored = stored("process-1");
        assertThat(stored).containsEntry("status", "CONFIRMED").containsEntry("reservation_id", 8251L)
                .containsEntry("professional_id", 15L).containsEntry("needs_reconciliation", false);
        assertThat(((Date) stored.get("appointment_date")).toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(((Time) stored.get("start_time")).toLocalTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(instant(stored, "confirmed_at")).isEqualTo(OCCURRED_AT);
        assertThat(output.getAll()).contains("WARN").contains("Catedra confirmed professional 15");
    }

    @Test
    void aRejectionSendsTheProcessBackToWaitingWithTheReasonAndKeepsTheRequest() {
        insertProcess("process-1", userId, "hold-1", "PHONE_SUBMITTED", true);
        jdbcTemplate.update("UPDATE reservation_processes SET request_event_id = 'request-event-1'");
        String eventId = newEventId();

        ACTIONS.publish("process-1", result("additional-information-rejected.json", eventId, "process-1", "hold-1"));

        awaitProcessed(eventId);
        Map<String, Object> stored = stored("process-1");
        assertThat(stored).containsEntry("status", "WAITING_FOR_PHONE").containsEntry("rejection_reason", "INVALID_PHONE_NUMBER")
                .containsEntry("request_event_id", "request-event-1").containsEntry("needs_reconciliation", true);
        assertThat(instant(stored, "expires_at")).isEqualTo(Instant.parse("2026-10-12T14:44:50Z"));
    }

    @Test
    void anExpirationClosesAProcessWithThePhoneSubmittedAndClearsTheMark() {
        insertProcess("process-1", userId, "hold-1", "PHONE_SUBMITTED", true);
        String eventId = newEventId();

        ACTIONS.publish("process-1", result("appointment-process-expired.json", eventId, "process-1", "hold-1"));

        awaitProcessed(eventId);
        assertThat(stored("process-1")).containsEntry("status", "EXPIRED").containsEntry("needs_reconciliation", false);
    }

    @Test
    void anInvalidProcessIsClosedAndMarkedForReconciliation() {
        insertProcess("process-1", userId, "hold-1", "WAITING_FOR_PHONE", false);
        String eventId = newEventId();

        ACTIONS.publish("process-1", result("appointment-process-invalid.json", eventId, "process-1", "hold-1"));

        awaitProcessed(eventId);
        assertThat(stored("process-1")).containsEntry("status", "INVALID").containsEntry("needs_reconciliation", true);
    }

    @Test
    void aCancellationClosesAConfirmedReservationAndKeepsItsConfirmation() {
        insertProcess("process-1", userId, "hold-1", "CONFIRMED", false);
        jdbcTemplate.update("UPDATE reservation_processes SET reservation_id = 8251, confirmed_at = ?",
                Timestamp.from(OCCURRED_AT));
        String eventId = newEventId();

        ACTIONS.publish("process-1", result("appointment-cancelled.json", eventId, "process-1", "hold-1"));

        awaitProcessed(eventId);
        Map<String, Object> stored = stored("process-1");
        assertThat(stored).containsEntry("status", "CANCELLED").containsEntry("reservation_id", 8251L)
                .containsEntry("cancellation_reason", "Cambio solicitado por el usuario");
        assertThat(instant(stored, "cancelled_at")).isEqualTo(Instant.parse("2026-10-13T15:10:00Z"));
        assertThat(instant(stored, "confirmed_at")).isEqualTo(OCCURRED_AT);
    }

    @Test
    void aResultDoesNotReopenAFinalStatusAndAConfirmationOverAClosedProcessIsAnError(CapturedOutput output) {
        insertProcess("process-1", userId, "hold-1", "EXPIRED", false);
        String eventId = newEventId();

        ACTIONS.publish("process-1", result("appointment-confirmed.json", eventId, "process-1", "hold-1"));

        awaitProcessed(eventId);
        assertThat(stored("process-1")).containsEntry("status", "EXPIRED");
        assertThat(stored("process-1").get("reservation_id")).isNull();
        assertThat(output.getAll()).contains("ERROR")
                .contains("Catedra confirmed reservation 8251 of reservation process process-1, which is already EXPIRED");
    }

    @Test
    void invalidMessagesAreDiscardedAndTheValidOneThatFollowsIsProcessed(CapturedOutput output) {
        insertProcess("process-1", userId, "hold-1", "PHONE_SUBMITTED", false);
        String eventId = newEventId();
        ObjectNode withoutReservation = node(result("appointment-confirmed.json", newEventId(), "process-1", "hold-1"));
        withoutReservation.remove("reservationId");
        ObjectNode otherVersion = node(result("appointment-process-expired.json", newEventId(), "process-1", "hold-1"));
        otherVersion.put("schemaVersion", 2);
        ObjectNode malformed = node(result("appointment-cancelled.json", newEventId(), "process-1", "hold-1"));
        malformed.put("occurredAt", "yesterday");

        ACTIONS.publish("process-1", withoutReservation.toString());
        ACTIONS.publish("process-1", otherVersion.toString());
        ACTIONS.publish("process-1", malformed.toString());
        ACTIONS.publish("process-1", result("appointment-confirmed.json", eventId, "process-1", "hold-1"));

        awaitProcessed(eventId);
        assertThat(stored("process-1")).containsEntry("status", "CONFIRMED");
        assertThat(processedEvents()).containsExactly(eventId);
        assertThat(output.getAll()).contains("missing reservationId").contains("unsupported schemaVersion 2")
                .contains("malformed occurredAt");
    }

    @Test
    void aRepeatedResultHasASingleEffectEvenIfTheProcessChangedInBetween(CapturedOutput output) {
        insertProcess("process-1", userId, "hold-1", "PHONE_SUBMITTED", false);
        String eventId = newEventId();
        String message = result("additional-information-rejected.json", eventId, "process-1", "hold-1");

        ACTIONS.publish("process-1", message);
        awaitProcessed(eventId);
        jdbcTemplate.update("UPDATE reservation_processes SET status = 'PHONE_SUBMITTED', rejection_reason = NULL");
        ACTIONS.publish("process-1", message);

        await().atMost(Duration.ofSeconds(30)).until(() -> ACTIONS.allConsumed()
                && output.getAll().contains("Reservation result " + eventId + " for reservation process process-1 was already processed"));
        assertThat(stored("process-1")).containsEntry("status", "PHONE_SUBMITTED");
        assertThat(stored("process-1").get("rejection_reason")).isNull();
        assertThat(processedEvents()).containsExactly(eventId);
    }

    @Test
    void anOrphanResultOrOneWithAnotherHoldChangesNothingAndIsNotRegistered(CapturedOutput output) {
        insertProcess("process-1", userId, "hold-1", "PHONE_SUBMITTED", false);
        String orphan = newEventId();
        String otherHold = newEventId();
        String next = newEventId();

        ACTIONS.publish("process-missing", result("appointment-process-expired.json", orphan, "process-missing", "hold-1"));
        ACTIONS.publish("process-1", result("appointment-process-expired.json", otherHold, "process-1", "hold-other"));
        ACTIONS.publish("process-1", result("appointment-process-invalid.json", next, "process-1", "hold-1"));

        awaitProcessed(next);
        assertThat(processedEvents()).containsExactly(next);
        assertThat(stored("process-1")).containsEntry("status", "INVALID");
        assertThat(output.getAll()).contains("Reservation result " + orphan + " refers to reservation process process-missing")
                .contains("Reservation result " + otherHold + " has hold hold-other");
    }

    @Test
    void aDatabaseFailureIsRetriedUntilTheResultIsSavedAndTheNextOneWaits(CapturedOutput output) {
        Long juan = newUser("juan.perez");
        insertProcess("process-1", userId, "hold-1", "PHONE_SUBMITTED", false);
        insertProcess("process-2", juan, "hold-2", "PHONE_SUBMITTED", false);
        String first = newEventId();
        String second = newEventId();
        database.failNext(3);

        ACTIONS.publish("process-1", result("appointment-confirmed.json", first, "process-1", "hold-1"));
        ACTIONS.publish("process-2", result("appointment-process-expired.json", second, "process-2", "hold-2"));

        awaitProcessed(second);
        assertThat(stored("process-1")).containsEntry("status", "CONFIRMED");
        assertThat(stored("process-2")).containsEntry("status", "EXPIRED");
        Map<String, Object> times = jdbcTemplate.queryForMap("""
                SELECT (SELECT processed_at FROM processed_events WHERE event_id = ?) AS first,
                       (SELECT processed_at FROM processed_events WHERE event_id = ?) AS second
                """, first, second);
        assertThat(((Timestamp) times.get("first")).toInstant())
                .isBeforeOrEqualTo(((Timestamp) times.get("second")).toInstant());
        assertThat(output.getAll()).contains("attempt 1 failed").contains("could not be saved after 2 attempts, trying again in PT0.2S");
    }

    // Se guardó pero no llegó a confirmarse el offset, como si el servicio se hubiera caído justo entre las dos cosas
    @Test
    void aResultSavedButNotConfirmedIsDeliveredAgainWithASingleEffect(CapturedOutput output) {
        insertProcess("process-1", userId, "hold-1", "PHONE_SUBMITTED", false);
        String eventId = newEventId();
        database.failOnceAfterSaving();

        ACTIONS.publish("process-1", result("appointment-confirmed.json", eventId, "process-1", "hold-1"));

        awaitProcessed(eventId);
        assertThat(stored("process-1")).containsEntry("status", "CONFIRMED");
        assertThat(processedEvents()).containsExactly(eventId);
        assertThat(output.getAll()).contains("Reservation result " + eventId + " for reservation process process-1 was already processed");
    }

    @Test
    void aReasonThatDoesNotFitOrHasTheNullCharacterIsSavedAsItCanAndDoesNotStopTheConsumption(CapturedOutput output) {
        Long juan = newUser("juan.perez");
        insertProcess("process-1", userId, "hold-1", "WAITING_FOR_PHONE", false);
        insertProcess("process-2", juan, "hold-2", "PHONE_SUBMITTED", false);
        String tooLong = newEventId();
        String withNull = newEventId();
        ObjectNode cancelled = node(result("appointment-cancelled.json", tooLong, "process-1", "hold-1"));
        cancelled.put("reason", "a".repeat(501));
        ObjectNode rejected = node(result("additional-information-rejected.json", withNull, "process-2", "hold-2"));
        rejected.put("reason", "INVALID\0_PHONE");

        ACTIONS.publish("process-1", cancelled.toString());
        ACTIONS.publish("process-2", rejected.toString());

        awaitProcessed(withNull);
        assertThat(stored("process-1")).containsEntry("status", "CANCELLED").containsEntry("cancellation_reason", "a".repeat(500));
        assertThat(stored("process-2")).containsEntry("status", "WAITING_FOR_PHONE").containsEntry("rejection_reason", "INVALID_PHONE");
        assertThat(output.getAll()).contains("could not be saved as it came");
    }

    @Test
    void anIdentifierThatDoesNotFitOrHasTheNullCharacterDoesNotStopTheConsumption(CapturedOutput output) {
        insertProcess("process-1", userId, "hold-1", "PHONE_SUBMITTED", false);
        String next = newEventId();
        ObjectNode withNull = node(result("appointment-process-expired.json", newEventId(), "process-1", "hold-1"));
        withNull.put("eventId", "with\0null");

        ACTIONS.publish("process-1", result("appointment-process-expired.json", "e".repeat(256), "process-1", "hold-1"));
        ACTIONS.publish("process-1", withNull.toString());
        ACTIONS.publish("process-1", result("appointment-confirmed.json", next, "process-1", "hold-1"));

        awaitProcessed(next);
        assertThat(stored("process-1")).containsEntry("status", "CONFIRMED");
        assertThat(processedEvents()).containsExactly(next);
        assertThat(output.getAll()).contains("eventId is too long").contains("eventId has the null character")
                .doesNotContain("could not be saved after");
    }

    // El teléfono se deja enviado escribiendo en la base: todavía no existe la operación que lo envía
    @Test
    void aUserWhoBlocksAndConfirmsASlotEndsUpWithAReservationAndCanBlockAnother(CapturedOutput output) {
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(200, PROFESSIONAL));
        CATEDRA.http().respond(HOLDS_PATH, FakeHttpServer.Response.of(201, grantedHold()));
        CATEDRA.http().respond(CONFIRM_PATH, FakeHttpServer.Response.of(202, acceptedConfirmation()));
        block().expectStatus().isCreated();
        confirm().expectStatus().isOk();
        String requestEvent = newEventId();
        ACTIONS.publish(PROCESS, phoneRequest(requestEvent));
        await().atMost(Duration.ofSeconds(30)).until(() -> requestEvent.equals(stored(PROCESS).get("request_event_id")));
        jdbcTemplate.update("UPDATE reservation_processes SET status = 'PHONE_SUBMITTED'");
        String confirmation = newEventId();
        ObjectNode confirmed = node(result("appointment-confirmed.json", confirmation, PROCESS, HOLD));
        confirmed.put("professionalId", 201);
        confirmed.put("date", MONDAY.toString());
        confirmed.put("startTime", "12:30");

        ACTIONS.publish(PROCESS, confirmed.toString());

        awaitProcessed(confirmation);
        assertThat(stored(PROCESS)).containsEntry("status", "CONFIRMED").containsEntry("reservation_id", 8251L)
                .containsEntry("request_event_id", requestEvent);
        assertThat(processedEvents()).containsExactlyInAnyOrder(requestEvent, confirmation);

        String conflict = confirm().expectStatus().isEqualTo(409).expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonPath.<String>read(conflict, "$.code")).isEqualTo("INVALID_PROCESS_STATE");
        assertThat(JsonPath.<String>read(conflict, "$.currentStatus")).isEqualTo("CONFIRMED");

        CATEDRA.http().respond(HOLDS_PATH, FakeHttpServer.Response.of(201, grantedHold()
                .replace(PROCESS, "process-another").replace(HOLD, "hold-another")));
        block().expectStatus().isCreated();
        assertThat(stored("process-another")).containsEntry("status", "HELD");
        assertThat(stored(PROCESS)).containsEntry("status", "CONFIRMED");
        assertThat(output.getAll()).doesNotContain(userToken).doesNotContain(FakeCatedraServer.TOKEN)
                .doesNotContain("clave-de-test").doesNotContain("Catedra confirmed professional");
    }

    private RestTestClient.ResponseSpec block() {
        return restTestClient.post().uri("/api/reservation-processes").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"professionalId\":201,\"date\":\"" + MONDAY + "\",\"startTime\":\"12:30\"}").exchange();
    }

    private RestTestClient.ResponseSpec confirm() {
        return restTestClient.post().uri("/api/reservation-processes/{id}/confirm", PROCESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange();
    }

    private void awaitProcessed(String eventId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> processedEvents().contains(eventId));
        await().atMost(Duration.ofSeconds(10)).until(ACTIONS::allConsumed);
    }

    private void insertProcess(String reservationProcessId, Long owner, String holdId, String status, boolean marked) {
        jdbcTemplate.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    appointment_date, start_time, status, expires_at, needs_reconciliation)
                VALUES (?, ?, ?, 201, ?, TIME '12:30', ?, ?, ?)
                """, reservationProcessId, holdId, owner, Date.valueOf(MONDAY), status, Timestamp.from(expiresAt), marked);
    }

    private Long newUser(String login) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated,
                                   created_date, last_modified_date)
                VALUES (?, 'hash', 'Juan', 'Pérez', ?, 'es', true, now(), now())
                RETURNING id
                """, Long.class, login, login + "@example.com");
    }

    private Map<String, Object> stored(String reservationProcessId) {
        return jdbcTemplate.queryForMap("SELECT * FROM reservation_processes WHERE reservation_process_id = ?", reservationProcessId);
    }

    private static Instant instant(Map<String, Object> stored, String column) {
        return ((Timestamp) stored.get(column)).toInstant();
    }

    private List<String> processedEvents() {
        return jdbcTemplate.queryForList("SELECT event_id FROM processed_events", String.class);
    }

    private static String result(String file, String eventId, String reservationProcessId, String holdId) {
        ObjectNode result = node(recording("kafka/" + file));
        result.put("eventId", eventId);
        result.put("reservationProcessId", reservationProcessId);
        result.put("holdId", holdId);
        return result.toString();
    }

    private String phoneRequest(String eventId) {
        ObjectNode request = node(recording("kafka/additional-information-requested.json"));
        request.put("eventId", eventId);
        request.put("reservationProcessId", PROCESS);
        request.put("holdId", HOLD);
        request.put("expiresAt", expiresAt.toString());
        return request.toString();
    }

    private static String newEventId() {
        return "event-" + UUID.randomUUID();
    }

    private static ObjectNode node(String json) {
        return (ObjectNode) JSON.readTree(json);
    }

    // La respuesta real grabada, con un vencimiento dentro de diez minutos para que el proceso siga abierto
    private String grantedHold() {
        return recording("hold-201-created.json").replace("2026-10-27T04:44:38.627744064Z", expiresAt.toString());
    }

    private String acceptedConfirmation() {
        return recording("confirm-202-accepted.json")
                .replace("2026-10-28T05:04:54.812004682Z", expiresAt.toString())
                .replace("process-bfcb30eb-6e39-402f-bd92-95e73bfec54d", PROCESS);
    }

    private static String recording(String name) {
        try (InputStream input = ReservationResultKafkaIntegrationTest.class.getResourceAsStream("/catedra/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
