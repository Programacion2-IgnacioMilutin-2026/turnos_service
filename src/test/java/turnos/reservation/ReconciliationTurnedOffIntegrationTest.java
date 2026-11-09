package turnos.reservation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Apagada, la reconciliación no consulta a la cátedra aunque haya procesos marcados y abiertos
@SpringBootTest(properties = "turnos.reservation.reconciliation.interval=100ms")
@Import(TestcontainersConfiguration.class)
class ReconciliationTurnedOffIntegrationTest {

    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "cuenta-tecnica-de-test");
        registry.add("turnos.catedra.password", () -> "clave-de-test");
        registry.add("turnos.reservation.reconciliation.enabled", () -> "false");
    }

    @AfterAll
    static void stopServers() {
        CATEDRA.close();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TechnicalSessionRepository technicalSessionRepository;

    @Test
    void aMarkedProcessIsNeverCheckedWithCatedra() {
        await().atMost(Duration.ofSeconds(10)).until(() -> technicalSessionRepository.get().isEnabled());
        Instant now = Instant.now();
        Long owner = jdbcTemplate.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated, created_date, last_modified_date)
                VALUES ('maria.gomez', 'hash', 'María', 'Gómez', 'maria.gomez@example.com', 'es', true, ?, ?) RETURNING id
                """, Long.class, Timestamp.from(now), Timestamp.from(now));
        try {
            jdbcTemplate.update("""
                    INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                        professional_first_name, professional_last_name, professional_category_name, appointment_date,
                        start_time, status, expires_at, needs_reconciliation)
                    VALUES ('process-1', 'hold-1', ?, 201, 'Ana', 'Gomez', 'Clinica medica', ?, ?, 'PHONE_SUBMITTED', ?, true)
                    """, owner, Date.valueOf(LocalDate.of(2026, 11, 9)), Time.valueOf(LocalTime.of(10, 30)),
                    Timestamp.from(now.plus(Duration.ofMinutes(10))));

            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> CATEDRA.listings().isEmpty());
            assertThat(jdbcTemplate.queryForObject("SELECT needs_reconciliation FROM reservation_processes", Boolean.class)).isTrue();
        } finally {
            jdbcTemplate.update("DELETE FROM reservation_processes");
            jdbcTemplate.update("DELETE FROM users");
        }
    }
}
