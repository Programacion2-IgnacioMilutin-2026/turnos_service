package turnos.reservation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import turnos.TestcontainersConfiguration;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// El barrido encendido contra un Postgres que deja de responder de verdad: una falla simulada al guardar no muestra lo
// que pasa con la base caída, donde lo primero que falla es una lectura. El contexto se descarta al terminar: si quedara
// en el caché de la suite, el barrido seguiría corriendo durante los demás tests
@SpringBootTest
@Import({TestcontainersConfiguration.class, ControllableProcessRepository.Configuration.class})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DatabaseDownOverdueSweepIntegrationTest {

    private static final String PROCESS = "process-1";
    private static final String PHONE = "+5492615551234";

    // Esperas cortas para que el test sea rápido: la base caída se detecta enseguida
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "500");
        registry.add("TURNOS_DB_SOCKET_TIMEOUT", () -> "2");
        registry.add("turnos.reservation.sweep.enabled", () -> "true");
        registry.add("turnos.reservation.sweep.interval", () -> "200ms");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PostgreSQLContainer postgres;
    @Autowired
    private ControllableProcessRepository database;

    private Long userId;
    private volatile boolean paused;

    @BeforeEach
    void createUser() {
        database.reset();
        userId = jdbc.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated,
                                   created_date, last_modified_date)
                VALUES ('juan.perez', 'hash', 'Juan', 'Perez', 'juan@example.com', 'es', true, now(), now())
                RETURNING id
                """, Long.class);
    }

    @AfterEach
    void cleanDatabase() {
        database.reset();
        unpauseDatabase();
        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).until(() -> {
            jdbc.update("DELETE FROM reservation_processes");
            jdbc.update("DELETE FROM users");
            return true;
        });
    }

    // Un barrido queda retenido justo después de buscar; así el proceso se guarda antes de la caída y nadie lo cierra
    @Test
    void aSearchThatFailsIsLoggedAndTheSweepClosesTheProcessWhenTheDatabaseComesBack(CapturedOutput output) throws InterruptedException {
        CountDownLatch searched = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean heldOnce = new AtomicBoolean();
        database.afterOverdueSearch(found -> {
            if (heldOnce.compareAndSet(false, true)) {
                searched.countDown();
                awaitLatch(release);
            }
        });
        assertThat(searched.await(10, TimeUnit.SECONDS)).isTrue();
        insertOverdueProcess("WAITING_FOR_PHONE");
        // Pasado este tiempo, el pool comprueba que la conexión siga viva antes de usarla: falla sin llegar a leer
        Thread.sleep(1000);

        pauseDatabase();
        release.countDown();
        await().atMost(Duration.ofSeconds(20)).until(() -> output.getAll().contains("Overdue sweep could not search for processes"));
        assertThat(output.getAll()).doesNotContain(PHONE);
        unpauseDatabase();

        awaitStored("status", "EXPIRED");
    }

    @Test
    void aReadThatFailsAfterTheSearchIsLoggedForThatProcessAndTheSweepClosesItWhenTheDatabaseComesBack(CapturedOutput output) {
        database.afterOverdueSearch(found -> {
            if (!found.isEmpty() && !paused) {
                pauseDatabase();
            }
        });
        insertOverdueProcess("WAITING_FOR_PHONE");

        await().atMost(Duration.ofSeconds(20)).until(() -> output.getAll().contains("Overdue reservation process " + PROCESS + " could not be resolved"));
        assertThat(output.getAll()).contains("0 closed, 0 marked for reconciliation, 1 failed").doesNotContain(PHONE);
        database.afterOverdueSearch(found -> { });
        unpauseDatabase();

        awaitStored("status", "EXPIRED");
    }

    // El último punto en que puede caerse: con el proceso releído y el candado tomado, tiene que terminar y soltarlo
    @Test
    void aSaveThatFailsIsLoggedWithoutThePhoneAndTheSweepMarksTheProcessWhenTheDatabaseComesBack(CapturedOutput output) {
        AtomicBoolean pausedOnce = new AtomicBoolean();
        database.failUpdatesWhen(process -> {
            if (pausedOnce.compareAndSet(false, true)) {
                pauseDatabase();
            }
            return false;
        });
        insertOverdueProcess("PHONE_SUBMITTED");

        await().atMost(Duration.ofSeconds(20)).until(() -> output.getAll().contains("Overdue reservation process " + PROCESS + " could not be resolved"));
        assertThat(output.getAll()).contains("0 closed, 0 marked for reconciliation, 1 failed").doesNotContain(PHONE);
        unpauseDatabase();

        awaitStored("needs_reconciliation", true);
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_processes WHERE reservation_process_id = ?", String.class, PROCESS)).isEqualTo("PHONE_SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT phone_number FROM reservation_processes WHERE reservation_process_id = ?", String.class, PROCESS)).isEqualTo(PHONE);
    }

    private void awaitStored(String column, Object expected) {
        await().ignoreExceptions().atMost(Duration.ofSeconds(20)).until(() -> expected.equals(
                jdbc.queryForObject("SELECT " + column + " FROM reservation_processes WHERE reservation_process_id = ?", expected.getClass(), PROCESS)));
    }

    private void insertOverdueProcess(String status) {
        jdbc.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    professional_first_name, professional_last_name, professional_category_name, appointment_date,
                    start_time, status, expires_at, phone_number)
                VALUES (?, 'hold-1', ?, 201, 'Ana', 'Gomez', 'Clinica medica', DATE '2026-11-09', TIME '10:30',
                        ?, now() - INTERVAL '1 hour', ?)
                """, PROCESS, userId, status, PHONE);
    }

    private void pauseDatabase() {
        paused = true;
        DockerClientFactory.instance().client().pauseContainerCmd(postgres.getContainerId()).exec();
    }

    private void unpauseDatabase() {
        if (paused) {
            paused = false;
            DockerClientFactory.instance().client().unpauseContainerCmd(postgres.getContainerId()).exec();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
