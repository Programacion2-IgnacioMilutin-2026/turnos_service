package turnos.reservation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import turnos.KafkaTestContainer;
import turnos.KafkaTestTopic;
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Postgres caído de verdad, con su contenedor pausado, cuando llega un evento de la cátedra: la primera operación es una
// lectura, y tiene que reintentarse igual que una escritura. Esta clase tiene su propio contenedor: pausarlo no afecta a
// los demás tests
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class DatabaseDownKafkaIntegrationTest {

    private static final KafkaTestTopic ACTIONS = KafkaTestTopic.random("database-down");
    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();

    static {
        CATEDRA.respond(200, FakeCatedraServer.provisionedLoginResponse("PROVISIONED",
                KafkaTestContainer.bootstrapServers(), ACTIONS.topic(), ACTIONS.consumerGroup()));
    }

    // Esperas cortas para que el test sea rápido: la base caída se detecta enseguida y se reintenta seguido
    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "cuenta-tecnica-de-test");
        registry.add("turnos.catedra.password", () -> "clave-de-test");
        registry.add("turnos.catedra.kafka.enabled", () -> "true");
        registry.add("turnos.catedra.kafka.retry.initial-delay", () -> "10ms");
        registry.add("turnos.catedra.kafka.retry.max-attempts", () -> "2");
        registry.add("turnos.catedra.kafka.retry.pause", () -> "200ms");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "500");
    }

    @AfterAll
    static void stopServers() {
        CATEDRA.close();
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PostgreSQLContainer postgres;
    @Autowired
    private TechnicalSessionRepository technicalSessionRepository;

    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM reservation_processes");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void aMessageThatArrivesWithTheDatabaseDownWaitsAndIsProcessedWhenItComesBack(CapturedOutput output) throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(() -> technicalSessionRepository.get().isEnabled());
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated,
                                   created_date, last_modified_date)
                VALUES ('juan.perez', 'hash', 'Juan', 'Perez', 'juan@example.com', 'es', true, now(), now())
                RETURNING id
                """, Long.class);
        insertHeldProcess("process-1", userId);
        Long otherUserId = jdbc.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated,
                                   created_date, last_modified_date)
                VALUES ('ana.gomez', 'hash', 'Ana', 'Gomez', 'ana@example.com', 'es', true, now(), now())
                RETURNING id
                """, Long.class);
        insertHeldProcess("process-2", otherUserId);
        jdbc.update("UPDATE reservation_processes SET status = 'PHONE_SUBMITTED' WHERE reservation_process_id = 'process-2'");
        // Pasado este tiempo, el pool comprueba que la conexión siga viva antes de usarla
        Thread.sleep(1000);

        pauseDatabase();
        try {
            ACTIONS.publish("process-1", phoneRequest("request-1", "process-1"));
            ACTIONS.publish("process-2", confirmation("confirmation-2", "process-2"));

            await().atMost(Duration.ofSeconds(60)).until(() -> output.getAll().contains("could not be saved after"));
            assertThat(ACTIONS.committedOffset()).isEqualTo(-1);
            assertThat(output.getAll()).doesNotContain("discarded after");
        } finally {
            unpauseDatabase();
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> "request-1".equals(requestEventId("process-1")));
        await().atMost(Duration.ofSeconds(30)).until(() -> "CONFIRMED".equals(status("process-2")));
        assertThat(status("process-1")).isEqualTo("WAITING_FOR_PHONE");
        assertThat(jdbc.queryForList("SELECT event_id FROM processed_events", String.class))
                .containsExactlyInAnyOrder("request-1", "confirmation-2");
        await().atMost(Duration.ofSeconds(10)).until(ACTIONS::allConsumed);
        assertThat(output.getAll()).doesNotContain("discarded after");
    }

    private void insertHeldProcess(String reservationProcessId, Long owner) {
        jdbc.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    appointment_date, start_time, status, expires_at, needs_reconciliation)
                VALUES (?, 'hold-1', ?, 201, DATE '2026-11-09', TIME '12:30', 'HELD', now() + interval '10 minutes', false)
                """, reservationProcessId, owner);
    }

    private static String phoneRequest(String eventId, String reservationProcessId) {
        return """
                {"eventId":"%s","eventType":"AdditionalInformationRequested","reservationProcessId":"%s",
                 "holdId":"hold-1","requiredField":"PHONE_NUMBER","expiresAt":"%s","schemaVersion":1}
                """.formatted(eventId, reservationProcessId, Instant.now().plusSeconds(600));
    }

    private static String confirmation(String eventId, String reservationProcessId) {
        return """
                {"eventId":"%s","eventType":"AppointmentConfirmed","reservationProcessId":"%s","holdId":"hold-1",
                 "reservationId":8251,"occurredAt":"%s","schemaVersion":1}
                """.formatted(eventId, reservationProcessId, Instant.now());
    }

    private String requestEventId(String reservationProcessId) {
        return jdbc.queryForObject("SELECT request_event_id FROM reservation_processes WHERE reservation_process_id = ?",
                String.class, reservationProcessId);
    }

    private String status(String reservationProcessId) {
        return jdbc.queryForObject("SELECT status FROM reservation_processes WHERE reservation_process_id = ?",
                String.class, reservationProcessId);
    }

    private void pauseDatabase() {
        DockerClientFactory.instance().client().pauseContainerCmd(postgres.getContainerId()).exec();
    }

    private void unpauseDatabase() {
        DockerClientFactory.instance().client().unpauseContainerCmd(postgres.getContainerId()).exec();
    }
}
