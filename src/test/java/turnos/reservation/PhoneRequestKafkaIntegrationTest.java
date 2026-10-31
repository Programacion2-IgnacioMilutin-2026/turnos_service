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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.domain.ports.out.TransactionRunner;
import turnos.reservation.infrastructure.persistence.adapter.SpringTransactionRunnerAdapter;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa con Postgres y Kafka reales (Testcontainers) y la cátedra REST y el catálogo simulados.
// El login de la cátedra simulada informa el Kafka del contenedor, así que el consumo arranca como en producción;
// el test publica en el topic de acciones lo que publicaría la cátedra
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, PhoneRequestKafkaIntegrationTest.ControllableDatabase.class})
@ExtendWith(OutputCaptureExtension.class)
class PhoneRequestKafkaIntegrationTest {

    private static final KafkaTestTopic ACTIONS = KafkaTestTopic.random("integration");
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
    void aUserWhoBlocksAndConfirmsASlotReceivesThePhoneRequestOfTheCatedra(CapturedOutput output) {
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(200, PROFESSIONAL));
        CATEDRA.http().respond(HOLDS_PATH, FakeHttpServer.Response.of(201, grantedHold()));
        CATEDRA.http().respond(CONFIRM_PATH, FakeHttpServer.Response.of(202, acceptedConfirmation()));
        restTestClient.post().uri("/api/reservation-processes").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"professionalId\":201,\"date\":\"" + MONDAY + "\",\"startTime\":\"12:30\"}")
                .exchange().expectStatus().isCreated();
        String confirmed = restTestClient.post().uri("/api/reservation-processes/{id}/confirm", PROCESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonPath.<String>read(confirmed, "$.status")).isEqualTo("WAITING_FOR_PHONE");
        assertThat(confirmed).doesNotContain("requestEventId");
        String eventId = newEventId();

        ACTIONS.publish(PROCESS, request(eventId, PROCESS, HOLD));

        await().atMost(Duration.ofSeconds(30)).until(() -> eventId.equals(stored(PROCESS).get("request_event_id")));
        assertThat(stored(PROCESS)).containsEntry("status", "WAITING_FOR_PHONE");
        assertThat(processedEvents()).containsExactly(eventId);
        await().atMost(Duration.ofSeconds(10)).until(ACTIONS::allConsumed);
        assertThat(output.getAll()).contains("Phone request " + eventId + " saved for reservation process " + PROCESS)
                .doesNotContain(userToken).doesNotContain(FakeCatedraServer.TOKEN).doesNotContain("clave-de-test");
    }

    @Test
    void aProcessWaitingForThePhoneKeepsTheRequestAndItsExpiration() {
        insertProcess("process-1", userId, "hold-1", "WAITING_FOR_PHONE", false);
        String eventId = newEventId();
        Instant requestExpiresAt = expiresAt.plusSeconds(5);

        ACTIONS.publish("process-1", request(eventId, "process-1", "hold-1", requestExpiresAt));

        await().atMost(Duration.ofSeconds(30)).until(() -> eventId.equals(stored("process-1").get("request_event_id")));
        assertThat(stored("process-1")).containsEntry("status", "WAITING_FOR_PHONE");
        assertThat(storedExpiresAt("process-1")).isEqualTo(requestExpiresAt);
        assertThat(processedEvents()).containsExactly(eventId);
        await().atMost(Duration.ofSeconds(10)).until(ACTIONS::allConsumed);
    }

    @Test
    void aHeldAndMarkedProcessStartsWaitingForThePhoneAndKeepsItsMark() {
        insertProcess("process-1", userId, "hold-1", "HELD", true);
        String eventId = newEventId();

        ACTIONS.publish("process-1", request(eventId, "process-1", "hold-1"));

        await().atMost(Duration.ofSeconds(30)).until(() -> eventId.equals(stored("process-1").get("request_event_id")));
        assertThat(stored("process-1")).containsEntry("status", "WAITING_FOR_PHONE").containsEntry("needs_reconciliation", true);
    }

    @Test
    void invalidMessagesAreDiscardedAndTheValidOneThatFollowsIsProcessed(CapturedOutput output) {
        insertProcess("process-1", userId, "hold-1", "WAITING_FOR_PHONE", false);
        String eventId = newEventId();
        ObjectNode withoutHold = node(request(newEventId(), "process-1", "hold-1"));
        withoutHold.remove("holdId");
        ObjectNode otherVersion = node(request(newEventId(), "process-1", "hold-1"));
        otherVersion.put("schemaVersion", 2);
        ObjectNode unknownType = node(request(newEventId(), "process-1", "hold-1"));
        unknownType.put("eventType", "SomethingNew");

        ACTIONS.publish("process-1", withoutHold.toString());
        ACTIONS.publish("process-1", otherVersion.toString());
        ACTIONS.publish("process-1", "not json");
        ACTIONS.publish("process-1", unknownType.toString());
        ACTIONS.publish("process-1", fixture("appointment-confirmed.json"));
        ACTIONS.publish("process-1", request(eventId, "process-1", "hold-1"));

        await().atMost(Duration.ofSeconds(30)).until(() -> eventId.equals(stored("process-1").get("request_event_id")));
        await().atMost(Duration.ofSeconds(10)).until(ACTIONS::allConsumed);
        assertThat(processedEvents()).containsExactly(eventId);
        assertThat(output.getAll()).contains("missing holdId").contains("unsupported schemaVersion 2")
                .contains("unreadable message").contains("unknown eventType SomethingNew")
                .contains("AppointmentConfirmed is not processed yet");
    }

    @Test
    void aRepeatedRequestHasASingleEffectEvenIfTheProcessChangedInBetween(CapturedOutput output) {
        insertProcess("process-1", userId, "hold-1", "WAITING_FOR_PHONE", false);
        String eventId = newEventId();
        ACTIONS.publish("process-1", request(eventId, "process-1", "hold-1"));
        await().atMost(Duration.ofSeconds(30)).until(() -> eventId.equals(stored("process-1").get("request_event_id")));
        jdbcTemplate.update("UPDATE reservation_processes SET status = 'PHONE_SUBMITTED' WHERE reservation_process_id = 'process-1'");

        ACTIONS.publish("process-1", request(eventId, "process-1", "hold-1"));

        await().atMost(Duration.ofSeconds(10)).until(ACTIONS::allConsumed);
        assertThat(stored("process-1")).containsEntry("status", "PHONE_SUBMITTED");
        assertThat(processedEvents()).containsExactly(eventId);
        assertThat(output.getAll()).contains("Phone request " + eventId + " for reservation process process-1 was already processed");
    }

    @Test
    void requestsThatMatchNoOpenProcessChangeNothing(CapturedOutput output) {
        Long juan = newUser("juan.perez");
        Long ana = newUser("ana.lopez");
        insertProcess("process-submitted", userId, "hold-1", "PHONE_SUBMITTED", false);
        insertProcess("process-expired", juan, "hold-2", "EXPIRED", false);
        insertProcess("process-waiting", ana, "hold-3", "WAITING_FOR_PHONE", false);

        ACTIONS.publish("process-missing", request(newEventId(), "process-missing", "hold-9"));
        ACTIONS.publish("process-waiting", request(newEventId(), "process-waiting", "hold-other"));
        ACTIONS.publish("process-submitted", request(newEventId(), "process-submitted", "hold-1"));
        ACTIONS.publish("process-expired", request(newEventId(), "process-expired", "hold-2"));

        await().atMost(Duration.ofSeconds(30)).until(ACTIONS::allConsumed);
        assertThat(jdbcTemplate.queryForList("SELECT request_event_id FROM reservation_processes", String.class))
                .containsOnlyNulls();
        assertThat(processedEvents()).isEmpty();
        assertThat(output.getAll()).contains("refers to reservation process process-missing, which does not exist here")
                .contains("has hold hold-other, but reservation process process-waiting has hold hold-3")
                .contains("reservation process process-submitted is already PHONE_SUBMITTED")
                .contains("reservation process process-expired is already EXPIRED");
    }

    @Test
    void aDatabaseFailureIsRetriedUntilTheRequestIsSavedAndTheNextOneWaits(CapturedOutput output) {
        Long juan = newUser("juan.perez");
        insertProcess("process-1", userId, "hold-1", "WAITING_FOR_PHONE", false);
        insertProcess("process-2", juan, "hold-2", "WAITING_FOR_PHONE", false);
        String first = newEventId();
        String second = newEventId();
        database.failNext(3);

        ACTIONS.publish("process-1", request(first, "process-1", "hold-1"));
        ACTIONS.publish("process-2", request(second, "process-2", "hold-2"));

        await().atMost(Duration.ofSeconds(30)).until(() -> second.equals(stored("process-2").get("request_event_id")));
        assertThat(stored("process-1")).containsEntry("request_event_id", first);
        Map<String, Object> times = jdbcTemplate.queryForMap("""
                SELECT (SELECT processed_at FROM processed_events WHERE event_id = ?) AS first,
                       (SELECT processed_at FROM processed_events WHERE event_id = ?) AS second
                """, first, second);
        assertThat(((java.sql.Timestamp) times.get("first")).toInstant())
                .isBeforeOrEqualTo(((java.sql.Timestamp) times.get("second")).toInstant());
        await().atMost(Duration.ofSeconds(10)).until(ACTIONS::allConsumed);
        assertThat(output.getAll()).contains("attempt 1 failed").contains("could not be saved after 2 attempts, trying again in PT0.2S");
    }

    // Se guardó pero no llegó a confirmarse el offset, como si el servicio se hubiera caído justo entre las dos cosas
    @Test
    void aRequestSavedButNotConfirmedIsDeliveredAgainWithASingleEffect(CapturedOutput output) {
        insertProcess("process-1", userId, "hold-1", "WAITING_FOR_PHONE", false);
        String eventId = newEventId();
        database.failOnceAfterSaving();

        ACTIONS.publish("process-1", request(eventId, "process-1", "hold-1"));

        await().atMost(Duration.ofSeconds(30)).until(ACTIONS::allConsumed);
        assertThat(stored("process-1")).containsEntry("request_event_id", eventId);
        assertThat(processedEvents()).containsExactly(eventId);
        assertThat(output.getAll()).contains("Phone request " + eventId + " for reservation process process-1 was already processed");
    }

    @Test
    void withKafkaDownTheApiKeepsAnsweringAndConsumptionResumesWhenItIsBack() {
        insertProcess("process-1", userId, "hold-1", "WAITING_FOR_PHONE", false);
        String eventId = newEventId();
        KafkaTestContainer.pause();
        try {
            restTestClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON).body(CREDENTIALS)
                    .exchange().expectStatus().isOk();
        } finally {
            KafkaTestContainer.unpause();
        }

        ACTIONS.publish("process-1", request(eventId, "process-1", "hold-1"));

        await().atMost(Duration.ofSeconds(60)).until(() -> eventId.equals(stored("process-1").get("request_event_id")));
    }

    private Map<String, Object> stored(String reservationProcessId) {
        return jdbcTemplate.queryForMap("SELECT * FROM reservation_processes WHERE reservation_process_id = ?", reservationProcessId);
    }

    private Instant storedExpiresAt(String reservationProcessId) {
        return jdbcTemplate.queryForObject("SELECT expires_at FROM reservation_processes WHERE reservation_process_id = ?",
                java.sql.Timestamp.class, reservationProcessId).toInstant();
    }

    private java.util.List<String> processedEvents() {
        return jdbcTemplate.queryForList("SELECT event_id FROM processed_events", String.class);
    }

    private void insertProcess(String reservationProcessId, Long owner, String holdId, String status, boolean marked) {
        jdbcTemplate.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    appointment_date, start_time, status, expires_at, needs_reconciliation)
                VALUES (?, ?, ?, 201, ?, TIME '12:30', ?, ?, ?)
                """, reservationProcessId, holdId, owner, java.sql.Date.valueOf(MONDAY), status,
                java.sql.Timestamp.from(expiresAt), marked);
    }

    private Long newUser(String login) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated,
                                   created_date, last_modified_date)
                VALUES (?, 'hash', 'Juan', 'Pérez', ?, 'es', true, now(), now())
                RETURNING id
                """, Long.class, login, login + "@example.com");
    }

    private String request(String eventId, String reservationProcessId, String holdId) {
        return request(eventId, reservationProcessId, holdId, expiresAt);
    }

    private static String request(String eventId, String reservationProcessId, String holdId, Instant expiresAt) {
        ObjectNode request = node(fixture("additional-information-requested.json"));
        request.put("eventId", eventId);
        request.put("reservationProcessId", reservationProcessId);
        request.put("holdId", holdId);
        request.put("expiresAt", expiresAt.toString());
        return request.toString();
    }

    private static String newEventId() {
        return "request-event-" + UUID.randomUUID();
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

    private static String fixture(String name) {
        return recording("kafka/" + name);
    }

    private static String recording(String name) {
        try (InputStream input = PhoneRequestKafkaIntegrationTest.class.getResourceAsStream("/catedra/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // La base real, con dos fallas que se pueden pedir: no guardar (como una base caída) o guardar y fallar después,
    // como si la conexión se cortara antes de que el consumidor confirme el mensaje
    @TestConfiguration
    static class ControllableDatabase {
        @Bean
        @Primary
        ControllableTransactionRunner controllableTransactionRunner(SpringTransactionRunnerAdapter realDatabase) {
            return new ControllableTransactionRunner(realDatabase);
        }
    }

    static final class ControllableTransactionRunner implements TransactionRunner {

        private final TransactionRunner realDatabase;
        private final AtomicInteger failuresLeft = new AtomicInteger();
        private final AtomicBoolean failAfterSaving = new AtomicBoolean();

        ControllableTransactionRunner(TransactionRunner realDatabase) {
            this.realDatabase = realDatabase;
        }

        void failNext(int times) {
            failuresLeft.set(times);
        }

        void failOnceAfterSaving() {
            failAfterSaving.set(true);
        }

        void reset() {
            failuresLeft.set(0);
            failAfterSaving.set(false);
        }

        @Override
        public void inTransaction(Runnable work) {
            if (failuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new ReservationNotSavedException(new IllegalStateException("database down"));
            }
            realDatabase.inTransaction(work);
            if (failAfterSaving.getAndSet(false)) {
                throw new ReservationNotSavedException(new IllegalStateException("connection lost after saving"));
            }
        }
    }
}
