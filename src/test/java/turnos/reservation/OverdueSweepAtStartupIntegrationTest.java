package turnos.reservation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// El proceso ya está en la base cuando la aplicación termina de arrancar, como después de un reinicio: el bean que lo
// guarda se crea antes de que empiecen las tareas programadas. Con un intervalo de una hora, solo lo puede cerrar el
// barrido del arranque
@SpringBootTest
@Import({TestcontainersConfiguration.class, OverdueSweepAtStartupIntegrationTest.ProcessLeftBeforeStarting.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OverdueSweepAtStartupIntegrationTest {

    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();
    private static final String LEFT_BEFORE_STARTING = "left-before-starting";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "cuenta-tecnica-de-test");
        registry.add("turnos.catedra.password", () -> "clave-de-test");
        registry.add("turnos.reservation.sweep.enabled", () -> "true");
        registry.add("turnos.reservation.sweep.interval", () -> "1h");
    }

    @AfterAll
    static void stopServers() {
        CATEDRA.close();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aProcessThatWasOverdueBeforeStartingIsClosedWithoutWaitingForTheInterval() {
        await().atMost(Duration.ofSeconds(10)).until(() -> "EXPIRED".equals(status(LEFT_BEFORE_STARTING)));

        insert(jdbcTemplate, "created-after-starting", newUser(jdbcTemplate, "pedro.perez"));

        // Si corriera a menudo también cerraría este: confirma que el anterior lo cerró la corrida del arranque
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> "HELD".equals(status("created-after-starting")));
        assertThat(status(LEFT_BEFORE_STARTING)).isEqualTo("EXPIRED");
    }

    private String status(String reservationProcessId) {
        return jdbcTemplate.queryForObject("SELECT status FROM reservation_processes WHERE reservation_process_id = ?", String.class, reservationProcessId);
    }

    private static Long newUser(JdbcTemplate jdbcTemplate, String login) {
        Instant now = Instant.now();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated, created_date, last_modified_date)
                VALUES (?, 'hash', 'María', 'Gómez', ?, 'es', true, ?, ?) RETURNING id
                """, Long.class, login, login + "@example.com", Timestamp.from(now), Timestamp.from(now));
    }

    private static void insert(JdbcTemplate jdbcTemplate, String reservationProcessId, Long owner) {
        jdbcTemplate.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    professional_first_name, professional_last_name, professional_category_name, appointment_date,
                    start_time, status, expires_at)
                VALUES (?, ?, ?, 201, 'Ana', 'Gomez', 'Clinica medica', ?, ?, 'HELD', ?)
                """, reservationProcessId, "hold-" + reservationProcessId, owner, Date.valueOf(LocalDate.of(2026, 11, 9)),
                Time.valueOf(LocalTime.of(10, 30)), Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProcessLeftBeforeStarting {

        @Bean
        @DependsOn("flywayInitializer")
        InitializingBean processLeftBeforeStarting(JdbcTemplate jdbcTemplate) {
            return () -> insert(jdbcTemplate, LEFT_BEFORE_STARTING, newUser(jdbcTemplate, "maria.gomez"));
        }
    }
}
