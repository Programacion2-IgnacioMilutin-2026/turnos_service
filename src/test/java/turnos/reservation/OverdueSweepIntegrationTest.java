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
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import turnos.FakeHttpServer;
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa con el barrido encendido, con Postgres real y catálogo y cátedra simulados. El contexto se
// descarta al terminar: si quedara en el caché de la suite, el barrido seguiría corriendo durante los demás tests
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, ControllableProcessRepository.Configuration.class})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OverdueSweepIntegrationTest {

    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();
    private static final String LOGIN_PATH = "/api/authenticate";
    private static final String HOLDS_PATH = "/api/appointment-holds";
    private static final String PROCESS = "process-1";
    private static final String PHONE = "+5492615551234";
    private static final Duration MARGIN = Duration.ofSeconds(60);
    private static final Duration INTERVAL = Duration.ofMillis(200);
    private static final LocalDate DATE = LocalDate.of(2026, 11, 9);
    private static final LocalDate NEXT_MONDAY = LocalDate.now(ZoneId.of("America/Argentina/Mendoza")).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    private static final String PROFESSIONAL = """
            {"id":201,"firstName":"Ana","lastName":"Gomez","enabled":true,
             "category":{"id":1,"name":"Clinica medica","enabled":true},
             "weeklySchedules":[{"id":21,"dayOfWeek":"MONDAY","startTime":"12:00:00","endTime":"15:00:00",
             "slotDurationMinutes":30,"enabled":true}]}
            """;

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("TURNOS_CATALOG_BASE_URL", CATALOG::baseUrl);
        registry.add("TURNOS_RESERVATION_SAFETY_MARGIN", () -> MARGIN.getSeconds() + "s");
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "cuenta-tecnica-de-test");
        registry.add("turnos.catedra.password", () -> "clave-de-test");
        registry.add("turnos.reservation.sweep.enabled", () -> "true");
        registry.add("turnos.reservation.sweep.interval", INTERVAL::toString);
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

    @BeforeEach
    void startClean() {
        await().atMost(Duration.ofSeconds(10)).until(() -> technicalSessionRepository.get().isEnabled());
        cleanDatabase();
        database.reset();
    }

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM reservation_processes");
        jdbcTemplate.update("DELETE FROM user_authorities");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void theSweepRunsOnItsOwnAndKeepsRunning() {
        insert("process-1", newUser("maria.gomez"), ReservationStatus.HELD, overdue());
        awaitStatus("process-1", ReservationStatus.EXPIRED);

        insert("process-2", newUser("pedro.perez"), ReservationStatus.WAITING_FOR_PHONE, overdue());
        awaitStatus("process-2", ReservationStatus.EXPIRED);
    }

    @Test
    void twoSweepsNeverRunAtTheSameTimeEvenIfOneTakesLongerThanTheInterval() {
        database.delayOverdueSearches(INTERVAL.multipliedBy(3));

        await().atMost(Duration.ofSeconds(20)).until(() -> database.overdueSearches() >= 3);

        assertThat(database.maxSimultaneousOverdueSearches()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void aMarkedProcessWaitingForTheUserIsClosedAndTheMarkIsTurnedOff(ReservationStatus status) {
        insert(PROCESS, newUser("maria.gomez"), status, overdue());
        jdbcTemplate.update("UPDATE reservation_processes SET needs_reconciliation = true WHERE reservation_process_id = ?", PROCESS);

        awaitStatus(PROCESS, ReservationStatus.EXPIRED);

        assertThat(marked(PROCESS)).isFalse();
    }

    @Test
    void aProcessWithThePhoneSubmittedIsMarkedOnceAndKeepsItsData() {
        insert(PROCESS, newUser("maria.gomez"), ReservationStatus.PHONE_SUBMITTED, overdue());
        jdbcTemplate.update("UPDATE reservation_processes SET phone_number = ? WHERE reservation_process_id = ?", PHONE, PROCESS);

        await().atMost(Duration.ofSeconds(10)).until(() -> marked(PROCESS));
        awaitSweepsToFinish();

        assertThat(status(PROCESS)).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(jdbcTemplate.queryForObject("SELECT phone_number FROM reservation_processes WHERE reservation_process_id = ?", String.class, PROCESS)).isEqualTo(PHONE);
        assertThat(database.updates()).isEqualTo(1);
    }

    @Test
    void aProcessInsideTheMarginAndAClosedOneAreNotTouched() {
        insert("inside-the-margin", newUser("maria.gomez"), ReservationStatus.WAITING_FOR_PHONE, Instant.now().minus(MARGIN).plusSeconds(120));
        insert("confirmed", newUser("pedro.perez"), ReservationStatus.CONFIRMED, overdue());
        insert("overdue", newUser("ana.diaz"), ReservationStatus.HELD, overdue());

        awaitStatus("overdue", ReservationStatus.EXPIRED);
        awaitSweepsToFinish();

        assertThat(status("inside-the-margin")).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(status("confirmed")).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(database.updates()).isEqualTo(1);
    }

    @Test
    void aReservationConfirmedAfterTheSearchIsNotOverwritten() {
        Long user = newUser("maria.gomez");
        AtomicBoolean confirmed = new AtomicBoolean();
        database.afterOverdueSearch(found -> {
            if (found.stream().map(ReservationProcess::getReservationProcessId).anyMatch(PROCESS::equals) && !confirmed.get()) {
                confirmInTheDatabase();
                confirmed.set(true);
            }
        });
        insert(PROCESS, user, ReservationStatus.WAITING_FOR_PHONE, overdue());

        await().atMost(Duration.ofSeconds(10)).untilTrue(confirmed);
        awaitSweepsToFinish();

        assertThat(status(PROCESS)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(database.updates()).isZero();
    }

    @Test
    void theSweepWaitsForTheOperationThatHoldsTheLockAndRespectsWhatItLeft() throws InterruptedException {
        Long user = newUser("maria.gomez");
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch found = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> userOperationLock.exclusivelyFor(user, () -> {
            taken.countDown();
            awaitLatch(release);
            confirmInTheDatabase();
            return null;
        }));
        assertThat(taken.await(5, TimeUnit.SECONDS)).isTrue();
        database.afterOverdueSearch(processes -> {
            if (!processes.isEmpty()) {
                found.countDown();
            }
        });
        insert(PROCESS, user, ReservationStatus.WAITING_FOR_PHONE, overdue());
        assertThat(found.await(10, TimeUnit.SECONDS)).isTrue();

        int searches = database.overdueSearches();
        Thread.sleep(INTERVAL.multipliedBy(3));

        assertThat(status(PROCESS)).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(database.overdueSearches()).isEqualTo(searches);

        release.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() -> database.overdueSearches() > searches);

        assertThat(status(PROCESS)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(database.updates()).isZero();
    }

    @Test
    void aFailureSavingOneProcessDoesNotStopTheOthersAndTheNextSweepClosesIt(CapturedOutput output) {
        database.failUpdatesWith("could not write the row with " + PHONE);
        database.failUpdatesWhen(process -> process.getReservationProcessId().equals("process-2"));
        insert("process-1", newUser("maria.gomez"), ReservationStatus.HELD, Instant.now().minus(MARGIN).minusSeconds(300));
        insert("process-2", newUser("pedro.perez"), ReservationStatus.WAITING_FOR_PHONE, Instant.now().minus(MARGIN).minusSeconds(200));
        jdbcTemplate.update("UPDATE reservation_processes SET phone_number = ? WHERE reservation_process_id = ?", PHONE, "process-2");
        insert("process-3", newUser("ana.diaz"), ReservationStatus.HELD, Instant.now().minus(MARGIN).minusSeconds(100));

        awaitStatus("process-1", ReservationStatus.EXPIRED);
        awaitStatus("process-3", ReservationStatus.EXPIRED);
        await().atMost(Duration.ofSeconds(10)).until(() -> output.toString().contains("Overdue reservation process process-2 could not be resolved: IllegalStateException"));

        assertThat(status("process-2")).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(output).contains("failed");
        assertThat(output).doesNotContain(PHONE);

        database.failUpdatesWhen(process -> false);

        awaitStatus("process-2", ReservationStatus.EXPIRED);
    }

    @Test
    void theSweepLogsItsResult(CapturedOutput output) {
        await().atMost(Duration.ofSeconds(10)).until(() -> output.toString().contains("0 closed, 0 marked for reconciliation, 0 failed"));

        insert(PROCESS, newUser("maria.gomez"), ReservationStatus.HELD, overdue());

        await().atMost(Duration.ofSeconds(10)).until(() -> output.toString().contains("1 closed, 0 marked for reconciliation, 0 failed"));
    }

    @Test
    void theSweepDoesNotCallTheCatedra() {
        insert("process-1", newUser("maria.gomez"), ReservationStatus.HELD, overdue());
        insert("process-2", newUser("pedro.perez"), ReservationStatus.PHONE_SUBMITTED, overdue());

        awaitStatus("process-1", ReservationStatus.EXPIRED);
        await().atMost(Duration.ofSeconds(10)).until(() -> marked("process-2"));
        awaitSweepsToFinish();

        assertThat(CATEDRA.receivedRequests()).allSatisfy(request -> assertThat(request.path()).isEqualTo(LOGIN_PATH));
    }

    @Test
    void aUserWhoseProcessWasClosedByTheSweepSeesItExpiredAndCanBlockAnotherSlot() {
        String token = registerAndAuthenticate("maria.gomez");
        Long user = jdbcTemplate.queryForObject("SELECT id FROM users WHERE login = 'maria.gomez'", Long.class);
        insert(PROCESS, user, ReservationStatus.WAITING_FOR_PHONE, overdue());

        awaitStatus(PROCESS, ReservationStatus.EXPIRED);

        String body = restTestClient.get().uri("/api/reservation-processes/{id}", PROCESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("EXPIRED");

        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(200, PROFESSIONAL));
        CATEDRA.http().respond(HOLDS_PATH, FakeHttpServer.Response.of(201, grantedHold()));
        restTestClient.post().uri("/api/reservation-processes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"professionalId\":201,\"date\":\"" + NEXT_MONDAY + "\",\"startTime\":\"12:00:00\"}")
                .exchange().expectStatus().isCreated();
    }

    // Con espera fija, ver empezar otra búsqueda completa después de esta significa que el barrido en curso terminó
    private void awaitSweepsToFinish() {
        int searches = database.overdueSearches();
        await().atMost(Duration.ofSeconds(10)).until(() -> database.overdueSearches() >= searches + 2);
    }

    private void awaitStatus(String reservationProcessId, ReservationStatus expected) {
        await().atMost(Duration.ofSeconds(10)).until(() -> status(reservationProcessId) == expected);
    }

    private ReservationStatus status(String reservationProcessId) {
        return ReservationStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM reservation_processes WHERE reservation_process_id = ?", String.class, reservationProcessId));
    }

    private Boolean marked(String reservationProcessId) {
        return jdbcTemplate.queryForObject(
                "SELECT needs_reconciliation FROM reservation_processes WHERE reservation_process_id = ?", Boolean.class, reservationProcessId);
    }

    private void confirmInTheDatabase() {
        jdbcTemplate.update("UPDATE reservation_processes SET status = 'CONFIRMED', reservation_id = 8251, confirmed_at = ? WHERE reservation_process_id = ?",
                Timestamp.from(Instant.now()), PROCESS);
    }

    private Long newUser(String login) {
        Instant now = Instant.now();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated, created_date, last_modified_date)
                VALUES (?, 'hash', 'María', 'Gómez', ?, 'es', true, ?, ?) RETURNING id
                """, Long.class, login, login + "@example.com", Timestamp.from(now), Timestamp.from(now));
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

    // La respuesta real grabada, con un vencimiento dentro de diez minutos para que el proceso siga abierto
    private static String grantedHold() {
        return recording("hold-201-created.json").replace("2026-10-27T04:44:38.627744064Z", Instant.now().plus(10, ChronoUnit.MINUTES).toString());
    }

    private static String recording(String name) {
        try (InputStream input = OverdueSweepIntegrationTest.class.getResourceAsStream("/catedra/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Instant overdue() {
        return Instant.now().minus(MARGIN).minus(10, ChronoUnit.MINUTES);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
