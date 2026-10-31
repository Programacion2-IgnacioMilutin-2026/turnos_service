package turnos.reservation;

import org.junit.jupiter.api.AfterAll;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import turnos.KafkaTestContainer;
import turnos.KafkaTestTopic;
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;
import turnos.catedra.application.service.TechnicalSessionService;
import turnos.catedra.domain.model.IntegrationState;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La cátedra no responde al arrancar: el servicio queda degradado y no consume. Los pedidos se acumulan en Kafka y,
// cuando un login posterior sale bien, el consumo arranca desde el más antiguo con un consumer group nuevo
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PhoneRequestKafkaAfterDegradedStartIntegrationTest {

    private static final KafkaTestTopic ACTIONS = KafkaTestTopic.random("degraded-start");
    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        CATEDRA.respond(503, FakeCatedraServer.problem(503, null));
    }

    // Un solo intento al arrancar y sin intervalo mínimo entre logins, para no esperar en el test
    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "cuenta-tecnica-de-test");
        registry.add("turnos.catedra.password", () -> "clave-de-test");
        registry.add("turnos.catedra.login.max-attempts", () -> "1");
        registry.add("turnos.catedra.login.min-interval", () -> "0s");
        registry.add("turnos.catedra.kafka.enabled", () -> "true");
    }

    @AfterAll
    static void stopCatedra() {
        CATEDRA.close();
    }

    @Autowired
    private TechnicalSessionRepository technicalSessionRepository;
    @Autowired
    private TechnicalSessionService technicalSessionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void requestsPublishedWhileDegradedAreProcessedFromTheOldestAfterALaterLogin(CapturedOutput output) throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(() -> technicalSessionRepository.get().state() == IntegrationState.DEGRADED);
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated,
                                   created_date, last_modified_date)
                VALUES ('maria.gomez', 'hash', 'María', 'Gómez', 'maria@example.com', 'es', true, now(), now())
                RETURNING id
                """, Long.class);
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        jdbcTemplate.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    appointment_date, start_time, status, expires_at)
                VALUES ('process-1', 'hold-1', ?, 201, DATE '2026-11-09', TIME '12:30', 'WAITING_FOR_PHONE', ?)
                """, userId, Timestamp.from(expiresAt));
        ACTIONS.publish("process-missing", request("request-event-orphan", "process-missing", "hold-9", expiresAt));
        ACTIONS.publish("process-1", request("request-event-1", "process-1", "hold-1", expiresAt));

        // Degradado: no consume
        Thread.sleep(2000);
        assertThat(ACTIONS.committedOffset()).isEqualTo(-1);
        assertThat(requestEventId()).isNull();

        CATEDRA.respond(200, FakeCatedraServer.provisionedLoginResponse("PROVISIONED",
                KafkaTestContainer.bootstrapServers(), ACTIONS.topic(), ACTIONS.consumerGroup()));
        assertThat(technicalSessionService.renewTechnicalSession(null)).isPresent();

        await().atMost(Duration.ofSeconds(30)).until(() -> "request-event-1".equals(requestEventId()));
        await().atMost(Duration.ofSeconds(10)).until(ACTIONS::allConsumed);
        assertThat(output.getAll())
                .contains("Phone request request-event-orphan refers to reservation process process-missing, which does not exist here");
        jdbcTemplate.update("DELETE FROM processed_events");
        jdbcTemplate.update("DELETE FROM reservation_processes");
        jdbcTemplate.update("DELETE FROM users");
    }

    private String requestEventId() {
        return jdbcTemplate.queryForObject(
                "SELECT request_event_id FROM reservation_processes WHERE reservation_process_id = 'process-1'", String.class);
    }

    private static String request(String eventId, String reservationProcessId, String holdId, Instant expiresAt) {
        ObjectNode request = (ObjectNode) JSON.readTree(fixture());
        request.put("eventId", eventId);
        request.put("reservationProcessId", reservationProcessId);
        request.put("holdId", holdId);
        request.put("expiresAt", expiresAt.toString());
        return request.toString();
    }

    private static String fixture() {
        try (InputStream input = PhoneRequestKafkaAfterDegradedStartIntegrationTest.class
                .getResourceAsStream("/catedra/kafka/additional-information-requested.json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
