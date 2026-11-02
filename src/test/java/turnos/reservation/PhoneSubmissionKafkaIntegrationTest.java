package turnos.reservation;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import turnos.FakeHttpServer;
import turnos.KafkaTestContainer;
import turnos.KafkaTestTopic;
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.infrastructure.persistence.adapter.JpaReservationProcessRepositoryAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

// La aplicación completa con Postgres y Kafka reales (Testcontainers) y la cátedra REST y el catálogo simulados.
// Se leen del topic de teléfono los mensajes que publica el servicio. Los tiempos de publicación son los de la
// configuración real, así que los tests con Kafka congelado tardan lo que tardaría un usuario
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, PhoneSubmissionKafkaIntegrationTest.ControllableDatabase.class})
@ExtendWith(OutputCaptureExtension.class)
class PhoneSubmissionKafkaIntegrationTest {

    private static final KafkaTestTopic ACTIONS = KafkaTestTopic.random("submissions");
    private static final KafkaTestTopic PHONE = KafkaTestTopic.random("submissions-phone");
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
    private static final String PHONE_1 = "+54 9 261 555-1111";
    private static final String NORMALIZED_1 = "+5492615551111";
    private static final String PHONE_2 = "(261) 555.2222";
    private static final String NORMALIZED_2 = "2615552222";

    static {
        ACTIONS.create();
        PHONE.create();
        CATEDRA.respond(200, FakeCatedraServer.provisionedLoginResponse("PROVISIONED",
                KafkaTestContainer.bootstrapServers(), ACTIONS.topic(), ACTIONS.consumerGroup(), PHONE.topic()));
    }

    // Esperas cortas para lo que no se está probando; las de publicar son las de la configuración real
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
    private ControllableProcessRepository database;

    private String userToken;
    private Long userId;
    private Instant expiresAt;
    private long testStartedAt;

    @BeforeEach
    void registerAndLogIn() {
        await().atMost(Duration.ofSeconds(10)).until(() -> technicalSessionRepository.get().isEnabled());
        database.reset();
        cleanDatabase();
        restTestClient.post().uri("/api/register").contentType(MediaType.APPLICATION_JSON).body(REGISTER_BODY)
                .exchange().expectStatus().isCreated();
        String body = restTestClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON).body(CREDENTIALS)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        userToken = JsonPath.read(body, "$.id_token");
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE login = 'maria.gomez'", Long.class);
        expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        testStartedAt = System.currentTimeMillis();
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
    void theUserInformsThePhoneAndTheMessageHasWhatTheCatedraExpects(CapturedOutput output) {
        String requestEvent = reachWaitingForThePhone();

        String answer = submit(PROCESS, PHONE_1).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(answer, "$.status")).isEqualTo("PHONE_SUBMITTED");
        assertThat(answer).doesNotContain("5551111").doesNotContain(HOLD).doesNotContain(requestEvent).doesNotContain("econciliation");
        assertThat(stored(PROCESS)).containsEntry("status", "PHONE_SUBMITTED").containsEntry("phone_number", NORMALIZED_1)
                .containsEntry("needs_reconciliation", false).containsEntry("rejection_reason", null);

        ConsumerRecord<String, String> message = published().getFirst();
        assertThat(published()).hasSize(1);
        assertThat(message.key()).isEqualTo(PROCESS);
        JsonNode received = JSON.readTree(message.value());
        ObjectNode expected = node(recording("kafka/additional-information-submitted.json"));
        expected.put("eventId", received.get("eventId").asString());
        expected.put("occurredAt", received.get("occurredAt").asString());
        expected.put("reservationProcessId", PROCESS);
        expected.put("holdId", HOLD);
        expected.put("requestEventId", requestEvent);
        ((ObjectNode) expected.get("information")).put("phoneNumber", NORMALIZED_1);
        assertThat(received).isEqualTo(expected);
        assertThat(received.get("eventId").asString()).startsWith("phone-submitted-");
        assertThat(Instant.parse(received.get("occurredAt").asString())).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(output.getAll()).doesNotContain("5551111").doesNotContain(FakeCatedraServer.TOKEN).doesNotContain("clave-de-test");
    }

    @Test
    void aRejectedPhoneCanBeInformedAgainAndTheReservationEndsUpConfirmed(CapturedOutput output) {
        String requestEvent = reachWaitingForThePhone();
        submit(PROCESS, PHONE_1).expectStatus().isOk();

        String rejection = newEventId();
        ACTIONS.publish(PROCESS, rejected(rejection));
        awaitProcessed(rejection);
        assertThat(stored(PROCESS)).containsEntry("status", "WAITING_FOR_PHONE").containsEntry("rejection_reason", "INVALID_PHONE_NUMBER")
                .containsEntry("request_event_id", requestEvent);

        submit(PROCESS, PHONE_2).expectStatus().isOk();

        assertThat(stored(PROCESS)).containsEntry("status", "PHONE_SUBMITTED").containsEntry("phone_number", NORMALIZED_2)
                .containsEntry("rejection_reason", null);
        List<ConsumerRecord<String, String>> messages = published();
        assertThat(messages).hasSize(2);
        JsonNode first = JSON.readTree(messages.get(0).value());
        JsonNode second = JSON.readTree(messages.get(1).value());
        assertThat(first.get("eventId")).isNotEqualTo(second.get("eventId"));
        assertThat(second.get("requestEventId").asString()).isEqualTo(requestEvent);
        assertThat(second.at("/information/phoneNumber").asString()).isEqualTo(NORMALIZED_2);

        String confirmation = newEventId();
        ACTIONS.publish(PROCESS, result("appointment-confirmed.json", confirmation));
        awaitProcessed(confirmation);
        assertThat(stored(PROCESS)).containsEntry("status", "CONFIRMED").containsEntry("reservation_id", 8251L)
                .containsEntry("phone_number", NORMALIZED_2);
        assertThat(output.getAll()).doesNotContain("5551111").doesNotContain("5552222");
    }

    @Test
    void aProcessThatDoesNotExistAndOneOfAnotherUserAnswerTheSameAndNothingIsPublished() {
        Long juan = newUser("juan.perez");
        insertProcess("process-of-juan", juan, "WAITING_FOR_PHONE", "request-event-1");

        String missing = submit("process-missing", PHONE_1).expectStatus().isNotFound().expectBody(String.class).returnResult().getResponseBody();
        String foreign = submit("process-of-juan", PHONE_1).expectStatus().isNotFound().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(foreign, "$.code")).isEqualTo("RESERVATION_PROCESS_NOT_FOUND")
                .isEqualTo(JsonPath.<String>read(missing, "$.code"));
        assertThat(JsonPath.<String>read(foreign, "$.detail")).isEqualTo(JsonPath.<String>read(missing, "$.detail"));
        assertThat(stored("process-of-juan")).containsEntry("status", "WAITING_FOR_PHONE").containsEntry("phone_number", null);
        assertThat(published()).isEmpty();
        restTestClient.post().uri("/api/reservation-processes/{id}/phone", "process-of-juan").contentType(MediaType.APPLICATION_JSON)
                .body("{\"phoneNumber\":\"2615551234\"}").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anInvalidPhoneIsRejectedEvenForAProcessThatDoesNotExist() {
        String answer = submit("process-missing", "abc").expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(answer, "$.code")).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(published()).isEmpty();
    }

    @Test
    void aProcessConfirmedByTheUserButWithoutThePhoneRequestIsNotReady() {
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(200, PROFESSIONAL));
        CATEDRA.http().respond(HOLDS_PATH, FakeHttpServer.Response.of(201, grantedHold()));
        CATEDRA.http().respond(CONFIRM_PATH, FakeHttpServer.Response.of(202, acceptedConfirmation()));
        block().expectStatus().isCreated();
        confirm().expectStatus().isOk();

        String answer = submit(PROCESS, PHONE_1).expectStatus().isEqualTo(409).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(answer, "$.code")).isEqualTo("PHONE_REQUEST_NOT_READY");
        assertThat(stored(PROCESS)).containsEntry("status", "WAITING_FOR_PHONE").containsEntry("phone_number", null);
        assertThat(published()).isEmpty();
    }

    @Test
    void anOverdueProcessIsClosedInTheDatabaseAndNothingIsPublished() {
        insertProcess(PROCESS, userId, "WAITING_FOR_PHONE", "request-event-1");
        jdbcTemplate.update("UPDATE reservation_processes SET expires_at = ?", Timestamp.from(Instant.now().minusSeconds(60)));

        String answer = submit(PROCESS, PHONE_1).expectStatus().isEqualTo(409).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(answer, "$.code")).isEqualTo("HOLD_EXPIRED");
        assertThat(stored(PROCESS)).containsEntry("status", "EXPIRED");
        assertThat(published()).isEmpty();
    }

    // Lo que deja una caída entre guardar y publicar: no hay nada que corra al arrancar, y el usuario no puede reenviarlo
    @Test
    void aProcessLeftWithThePhoneSubmittedAndNothingPublishedCannotBeSubmittedAgain() {
        insertProcess(PROCESS, userId, "PHONE_SUBMITTED", "request-event-1");

        String answer = submit(PROCESS, PHONE_1).expectStatus().isEqualTo(409).expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(answer, "$.code")).isEqualTo("INVALID_PROCESS_STATE");
        assertThat(JsonPath.<String>read(answer, "$.currentStatus")).isEqualTo("PHONE_SUBMITTED");
        assertThat(stored(PROCESS)).containsEntry("needs_reconciliation", false);
        assertThat(published()).isEmpty();
    }

    @Test
    void twoSimultaneousSubmissionsPublishOnce() throws InterruptedException {
        reachWaitingForThePhone();
        List<Integer> statuses = new CopyOnWriteArrayList<>();

        Thread first = Thread.ofVirtual().start(() -> statuses.add(submitStatus(PHONE_1)));
        Thread second = Thread.ofVirtual().start(() -> statuses.add(submitStatus(PHONE_2)));
        first.join(30000);
        second.join(30000);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(published()).hasSize(1);
    }

    @Test
    void withKafkaDownTheUserGetsAnAnswerInTimeThePhoneGoesBackAndCanBeSentLater(CapturedOutput output) {
        String requestEvent = reachWaitingForThePhone();
        submit(PROCESS, PHONE_1).expectStatus().isOk();
        String rejection = newEventId();
        ACTIONS.publish(PROCESS, rejected(rejection));
        awaitProcessed(rejection);

        KafkaTestContainer.pause();
        try {
            Instant started = Instant.now();
            String answer = submit(PROCESS, PHONE_2).expectStatus().isEqualTo(503).expectBody(String.class).returnResult().getResponseBody();
            Duration took = Duration.between(started, Instant.now());

            assertThat(took).isLessThanOrEqualTo(Duration.ofSeconds(10));
            assertThat(JsonPath.<String>read(answer, "$.code")).isEqualTo("CENTRAL_UNAVAILABLE");
            assertThat(answer).doesNotContain("Timeout").doesNotContain("5552222");
            assertThat(stored(PROCESS)).containsEntry("status", "WAITING_FOR_PHONE").containsEntry("needs_reconciliation", true)
                    .containsEntry("rejection_reason", null).containsEntry("phone_number", NORMALIZED_2)
                    .containsEntry("request_event_id", requestEvent);
            assertThat(output.getAll()).contains("attempt 1/3 failed").contains("attempt 2/3 failed").contains("attempt 3/3 failed")
                    .contains("failed after the retries");
        } finally {
            KafkaTestContainer.unpause();
        }

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
                .until(() -> submitStatus("+54 261 555-3333") == 200);

        assertThat(stored(PROCESS)).containsEntry("status", "PHONE_SUBMITTED").containsEntry("phone_number", "+542615553333");
        // Un intento vencido pudo haber llegado cuando Kafka volvió: lleva el otro teléfono y su propio evento
        List<JsonNode> messages = published().stream().map(message -> JSON.readTree(message.value())).toList();
        JsonNode last = messages.stream().filter(message -> message.at("/information/phoneNumber").asString().equals("+542615553333"))
                .reduce((previous, next) -> next).orElseThrow();
        assertThat(messages).filteredOn(message -> !message.at("/information/phoneNumber").asString().equals("+542615553333"))
                .noneMatch(message -> message.get("eventId").equals(last.get("eventId")));
        assertThat(last.get("requestEventId").asString()).isEqualTo(requestEvent);
        assertThat(output.getAll()).doesNotContain("5552222").doesNotContain("5551111").doesNotContain("5553333");
    }

    @Test
    void whenTheFirstSaveFailsNothingIsPublishedAndTheProcessStaysAsItWas(CapturedOutput output) {
        reachWaitingForThePhone();
        database.failUpdatesWhen(process -> process.getStatus() == ReservationStatus.PHONE_SUBMITTED);

        String answer = submit(PROCESS, PHONE_1).expectStatus().is5xxServerError().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(answer, "$.code")).isEqualTo("INTERNAL_ERROR");
        assertThat(stored(PROCESS)).containsEntry("status", "WAITING_FOR_PHONE").containsEntry("phone_number", null);
        assertThat(published()).isEmpty();
        assertThat(output.getAll()).contains("nothing was published").doesNotContain("5551111");
    }

    @Test
    void withKafkaDownAndTheDatabaseFailingTheStateIsOutOfSync(CapturedOutput output) {
        reachWaitingForThePhone();
        database.failUpdatesWhen(process -> process.getStatus() == ReservationStatus.WAITING_FOR_PHONE);

        KafkaTestContainer.pause();
        try {
            String answer = submit(PROCESS, PHONE_1).expectStatus().is5xxServerError().expectBody(String.class).returnResult().getResponseBody();

            assertThat(JsonPath.<String>read(answer, "$.code")).isEqualTo("LOCAL_STATE_OUT_OF_SYNC");
            assertThat(stored(PROCESS)).containsEntry("status", "PHONE_SUBMITTED").containsEntry("needs_reconciliation", false);
            assertThat(output.getAll()).contains("ERROR").contains("stays as PHONE_SUBMITTED although its phone was not published")
                    .doesNotContain("5551111");
        } finally {
            KafkaTestContainer.unpause();
        }
    }

    private String reachWaitingForThePhone() {
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(200, PROFESSIONAL));
        CATEDRA.http().respond(HOLDS_PATH, FakeHttpServer.Response.of(201, grantedHold()));
        CATEDRA.http().respond(CONFIRM_PATH, FakeHttpServer.Response.of(202, acceptedConfirmation()));
        block().expectStatus().isCreated();
        confirm().expectStatus().isOk();
        String requestEvent = newEventId();
        ACTIONS.publish(PROCESS, phoneRequest(requestEvent));
        await().atMost(Duration.ofSeconds(30)).until(() -> requestEvent.equals(stored(PROCESS).get("request_event_id")));
        await().atMost(Duration.ofSeconds(10)).until(ACTIONS::allConsumed);
        return requestEvent;
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

    private RestTestClient.ResponseSpec submit(String reservationProcessId, String phoneNumber) {
        return restTestClient.post().uri("/api/reservation-processes/{id}/phone", reservationProcessId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).contentType(MediaType.APPLICATION_JSON)
                .body("{\"phoneNumber\":\"" + phoneNumber + "\"}").exchange();
    }

    private int submitStatus(String phoneNumber) {
        return restTestClient.post().uri("/api/reservation-processes/{id}/phone", PROCESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).contentType(MediaType.APPLICATION_JSON)
                .body("{\"phoneNumber\":\"" + phoneNumber + "\"}").exchange().returnResult(String.class).getStatus().value();
    }

    // Lo publicado por este test, por el instante del mensaje: el topic lo comparten todos los de la clase, y un intento
    // vencido de un test anterior puede llegar cuando Kafka vuelve, después de que empezó este
    private List<ConsumerRecord<String, String>> published() {
        return PHONE.messages().stream().filter(message -> message.timestamp() >= testStartedAt).toList();
    }

    private void awaitProcessed(String eventId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> processedEvents().contains(eventId));
        await().atMost(Duration.ofSeconds(10)).until(ACTIONS::allConsumed);
    }

    private void insertProcess(String reservationProcessId, Long owner, String status, String requestEventId) {
        jdbcTemplate.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    appointment_date, start_time, status, expires_at, needs_reconciliation, request_event_id)
                VALUES (?, ?, ?, 201, ?, TIME '12:30', ?, ?, false, ?)
                """, reservationProcessId, HOLD, owner, Date.valueOf(MONDAY), status, Timestamp.from(expiresAt), requestEventId);
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

    private List<String> processedEvents() {
        return jdbcTemplate.queryForList("SELECT event_id FROM processed_events", String.class);
    }

    private static String result(String file, String eventId) {
        ObjectNode result = node(recording("kafka/" + file));
        result.put("eventId", eventId);
        result.put("reservationProcessId", PROCESS);
        result.put("holdId", HOLD);
        return result.toString();
    }

    private String rejected(String eventId) {
        ObjectNode rejected = node(result("additional-information-rejected.json", eventId));
        rejected.put("expiresAt", expiresAt.toString());
        return rejected.toString();
    }

    private String phoneRequest(String eventId) {
        ObjectNode request = node(result("additional-information-requested.json", eventId));
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
        try (InputStream input = PhoneSubmissionKafkaIntegrationTest.class.getResourceAsStream("/catedra/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControllableDatabase {

        @Bean
        @Primary
        ControllableProcessRepository controllableProcessRepository(JpaReservationProcessRepositoryAdapter realDatabase) {
            return new ControllableProcessRepository(realDatabase);
        }
    }

    // La base real, que falla al guardar cuando el test lo indica
    static final class ControllableProcessRepository implements ReservationProcessRepository {

        private final ReservationProcessRepository realDatabase;
        private volatile Predicate<ReservationProcess> failUpdate = process -> false;

        ControllableProcessRepository(ReservationProcessRepository realDatabase) {
            this.realDatabase = realDatabase;
        }

        void failUpdatesWhen(Predicate<ReservationProcess> condition) {
            failUpdate = condition;
        }

        void reset() {
            failUpdate = process -> false;
        }

        @Override
        public ReservationProcess create(ReservationProcess reservationProcess) {
            return realDatabase.create(reservationProcess);
        }

        @Override
        public Optional<ReservationProcess> update(String reservationProcessId, ReservationProcess reservationProcess) {
            if (failUpdate.test(reservationProcess)) {
                throw new IllegalStateException("database down");
            }
            return realDatabase.update(reservationProcessId, reservationProcess);
        }

        @Override
        public List<ReservationProcess> findOpenByUserId(Long userId) {
            return realDatabase.findOpenByUserId(userId);
        }

        @Override
        public Optional<ReservationProcess> findById(String reservationProcessId) {
            return realDatabase.findById(reservationProcessId);
        }

        @Override
        public Optional<ReservationProcess> findByIdAndUserId(String reservationProcessId, Long userId) {
            return realDatabase.findByIdAndUserId(reservationProcessId, userId);
        }

        @Override
        public void markForReconciliation(String reservationProcessId) {
            realDatabase.markForReconciliation(reservationProcessId);
        }
    }
}
