package turnos.reservation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import turnos.FakeHttpServer;
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// El proceso quedó abierto y sin marca antes de un reinicio, y la reconciliación de arranque corre antes de que termine
// el login técnico, que acá tarda dos segundos. Con un intervalo de una hora, solo la ejecución de arranque lo corrige
@SpringBootTest
@Import({TestcontainersConfiguration.class, ReconciliationAtStartupIntegrationTest.ProcessLeftBeforeStarting.class})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReconciliationAtStartupIntegrationTest {

    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();
    private static final String LOGIN_PATH = "/api/authenticate";
    private static final String LEFT_BEFORE_STARTING = "left-before-starting";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CATEDRA.http().respond(LOGIN_PATH, FakeHttpServer.Response.of(200,
                FakeCatedraServer.provisionedLoginResponse("PROVISIONED")).after(Duration.ofSeconds(2)));
        CATEDRA.respondToListings(patient -> FakeHttpServer.Response.of(200, "[" + FakeCatedraServer.appointment(
                LEFT_BEFORE_STARTING, patient, "PHONE_PENDING", 1664L, null, null, null) + "]"));
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "cuenta-tecnica-de-test");
        registry.add("turnos.catedra.password", () -> "clave-de-test");
        registry.add("turnos.reservation.reconciliation.enabled", () -> "true");
        registry.add("turnos.reservation.reconciliation.interval", () -> "1h");
    }

    @AfterAll
    static void stopServers() {
        CATEDRA.close();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void anOpenProcessLeftBeforeStartingIsCorrectedOnceTheInitialLoginFinishes(CapturedOutput output) {
        await().atMost(Duration.ofSeconds(20)).until(() -> "WAITING_FOR_PHONE".equals(status()));

        assertThat(jdbcTemplate.queryForObject("SELECT reservation_id FROM reservation_processes WHERE reservation_process_id = ?",
                Long.class, LEFT_BEFORE_STARTING)).isEqualTo(1664L);
        List<String> paths = CATEDRA.receivedRequests().stream().map(FakeHttpServer.ReceivedRequest::path).toList();
        assertThat(paths).containsSubsequence(LOGIN_PATH, FakeCatedraServer.APPOINTMENTS_PATH);
        assertThat(CATEDRA.listings()).hasSize(1);
        assertThat(output.getAll()).contains("waits for the initial technical login");
        assertThat(output.getAll()).doesNotContain("reservation processes left for the next cycle");
    }

    private String status() {
        return jdbcTemplate.queryForObject("SELECT status FROM reservation_processes WHERE reservation_process_id = ?",
                String.class, LEFT_BEFORE_STARTING);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProcessLeftBeforeStarting {

        @Bean
        @DependsOn("flywayInitializer")
        InitializingBean processLeftBeforeStarting(JdbcTemplate jdbcTemplate) {
            return () -> {
                Instant now = Instant.now();
                Long owner = jdbcTemplate.queryForObject("""
                        INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated, created_date, last_modified_date)
                        VALUES ('maria.gomez', 'hash', 'María', 'Gómez', 'maria.gomez@example.com', 'es', true, ?, ?) RETURNING id
                        """, Long.class, Timestamp.from(now), Timestamp.from(now));
                jdbcTemplate.update("""
                        INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                            professional_first_name, professional_last_name, professional_category_name, appointment_date,
                            start_time, status, expires_at)
                        VALUES (?, ?, ?, 201, 'Ana', 'Gomez', 'Clinica medica', ?, ?, 'HELD', ?)
                        """, LEFT_BEFORE_STARTING, "hold-" + LEFT_BEFORE_STARTING, owner, Date.valueOf(LocalDate.of(2026, 11, 9)),
                        Time.valueOf(LocalTime.of(10, 30)), Timestamp.from(now.plus(Duration.ofMinutes(10))));
            };
        }
    }
}
