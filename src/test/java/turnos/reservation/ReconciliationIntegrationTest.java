package turnos.reservation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import turnos.FakeHttpServer;
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;
import turnos.reservation.domain.model.ReservationStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa con la reconciliación y el barrido encendidos, con Postgres real y la cátedra simulada. El
// contexto se descarta al terminar: si quedara en el caché de la suite, las dos tareas seguirían corriendo
@SpringBootTest
@Import({TestcontainersConfiguration.class, ControllableProcessRepository.Configuration.class})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReconciliationIntegrationTest {

    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();
    private static final Duration INTERVAL = Duration.ofMillis(200);
    private static final LocalDate DATE = LocalDate.of(2026, 11, 9);
    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-05T12:05:00Z");
    private static final Instant CANCELLED_AT = Instant.parse("2026-11-06T09:00:00Z");
    // Las respuestas del listado por paciente; sin respuesta, el paciente no tiene reservas
    private static final Map<String, FakeHttpServer.Response> LISTINGS = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        CATEDRA.respondToListings(patient -> LISTINGS.getOrDefault(patient, FakeHttpServer.Response.of(200, "[]")));
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "cuenta-tecnica-de-test");
        registry.add("turnos.catedra.password", () -> "clave-de-test");
        registry.add("turnos.reservation.reconciliation.enabled", () -> "true");
        registry.add("turnos.reservation.reconciliation.interval", INTERVAL::toString);
        registry.add("turnos.reservation.sweep.enabled", () -> "true");
        registry.add("turnos.reservation.sweep.interval", INTERVAL::toString);
    }

    @AfterAll
    static void stopServers() {
        CATEDRA.close();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TechnicalSessionRepository technicalSessionRepository;
    @Autowired
    private ControllableProcessRepository database;

    @BeforeEach
    void startClean() {
        await().atMost(Duration.ofSeconds(10)).until(() -> technicalSessionRepository.get().isEnabled());
        cleanDatabase();
        database.reset();
    }

    @AfterEach
    void cleanDatabase() {
        LISTINGS.clear();
        jdbcTemplate.update("DELETE FROM reservation_processes");
        jdbcTemplate.update("DELETE FROM user_authorities");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void aMarkedProcessIsReconciledOnItsOwnWithTheReservationsOfItsOwner() {
        Long owner = newUser("maria.gomez");
        insert("process-1", owner, ReservationStatus.PHONE_SUBMITTED, true, inTenMinutes());
        LISTINGS.put(owner.toString(), list(FakeCatedraServer.appointment("process-1", owner.toString(), "CONFIRMED", 8251L,
                CONFIRMED_AT, null, null)));

        awaitStatus("process-1", ReservationStatus.CONFIRMED);

        Map<String, Object> stored = row("process-1");
        assertThat(stored.get("reservation_id")).isEqualTo(8251L);
        assertThat(((Timestamp) stored.get("confirmed_at")).toInstant()).isEqualTo(CONFIRMED_AT);
        assertThat(stored.get("needs_reconciliation")).isEqualTo(false);
        FakeHttpServer.ReceivedRequest listing = CATEDRA.listings().stream()
                .filter(request -> owner.toString().equals(FakeCatedraServer.patientOf(request))).findFirst().orElseThrow();
        assertThat(listing.method()).isEqualTo("GET");
        assertThat(listing.query()).isEqualTo("externalPatientId=" + owner + "&page=0&size=20");
        assertThat(listing.header("Authorization")).isEqualTo("Bearer " + FakeCatedraServer.TOKEN);
    }

    // Las reservas reales grabadas: una recién confirmada sin teléfono y otra vencida, del paciente de prueba
    @Test
    void theRealRecordingsCorrectAHeldProcessAndAnInvalidOne() {
        Long owner = newUser(999999L, "paciente.de.prueba");
        insert("process-d62d22ad-8b62-49e6-b6d6-296b99248000", owner, ReservationStatus.HELD, true, inTenMinutes());
        insert("process-bfcb30eb-6e39-402f-bd92-95e73bfec54d", owner, ReservationStatus.INVALID, true, inTenMinutes());
        LISTINGS.put("999999", FakeHttpServer.Response.of(200, recording("appointments-200-phone-pending.json")));

        awaitStatus("process-d62d22ad-8b62-49e6-b6d6-296b99248000", ReservationStatus.WAITING_FOR_PHONE);
        awaitStatus("process-bfcb30eb-6e39-402f-bd92-95e73bfec54d", ReservationStatus.EXPIRED);

        Map<String, Object> waiting = row("process-d62d22ad-8b62-49e6-b6d6-296b99248000");
        assertThat(waiting.get("reservation_id")).isEqualTo(1664L);
        assertThat(waiting.get("needs_reconciliation")).isEqualTo(false);
        assertThat(marked("process-bfcb30eb-6e39-402f-bd92-95e73bfec54d")).isFalse();
    }

    @Test
    void aConfirmationThatCatedraCancelledIsCancelledWithItsDateAndReason() {
        Long owner = newUser("maria.gomez");
        insert("process-1", owner, ReservationStatus.CONFIRMED, true, inTenMinutes());
        LISTINGS.put(owner.toString(), list(FakeCatedraServer.appointment("process-1", owner.toString(), "CANCELLED", 8251L,
                CONFIRMED_AT, CANCELLED_AT, "Paciente no asiste")));

        awaitStatus("process-1", ReservationStatus.CANCELLED);

        Map<String, Object> stored = row("process-1");
        assertThat(((Timestamp) stored.get("cancelled_at")).toInstant()).isEqualTo(CANCELLED_AT);
        assertThat(stored.get("cancellation_reason")).isEqualTo("Paciente no asiste");
        assertThat(stored.get("needs_reconciliation")).isEqualTo(false);
    }

    @Test
    void withoutARecordAHeldProcessStaysOpenAndOneWaitingForThePhoneExpires() {
        insert("process-1", newUser("maria.gomez"), ReservationStatus.HELD, true, inTenMinutes());
        insert("process-2", newUser("pedro.perez"), ReservationStatus.WAITING_FOR_PHONE, true, inTenMinutes());

        awaitStatus("process-2", ReservationStatus.EXPIRED);
        await().atMost(Duration.ofSeconds(10)).until(() -> !marked("process-1"));

        assertThat(status("process-1")).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void aSubmittedPhoneThatCatedraHasNotResolvedIsCheckedEveryCycle() {
        Long owner = newUser("maria.gomez");
        insert("process-1", owner, ReservationStatus.PHONE_SUBMITTED, true, inTenMinutes());
        LISTINGS.put(owner.toString(), list(FakeCatedraServer.appointment("process-1", owner.toString(), "PHONE_PENDING", 1664L,
                null, null, null)));

        await().atMost(Duration.ofSeconds(10)).until(() -> listingsOf(owner) >= 3);

        assertThat(status("process-1")).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(marked("process-1")).isTrue();
    }

    @Test
    void theRealFailureOfCatedraLeavesTheProcessForALaterCycle(CapturedOutput output) {
        Long owner = newUser("maria.gomez");
        insert("process-1", owner, ReservationStatus.PHONE_SUBMITTED, true, inTenMinutes());
        LISTINGS.put(owner.toString(), FakeHttpServer.Response.of(500, recording("appointments-500-date-filter.json")));

        await().atMost(Duration.ofSeconds(20)).until(() -> output.getAll().contains("reservation processes left for the next cycle"));
        assertThat(status("process-1")).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(marked("process-1")).isTrue();

        LISTINGS.put(owner.toString(), list(FakeCatedraServer.appointment("process-1", owner.toString(), "FAILED", 8251L,
                null, null, null)));
        awaitStatus("process-1", ReservationStatus.EXPIRED);
    }

    @Test
    void aFailureSavingOneProcessLeavesItMarkedAndALaterCycleCorrectsIt(CapturedOutput output) {
        Long first = newUser("maria.gomez");
        Long second = newUser("pedro.perez");
        insert("process-1", first, ReservationStatus.PHONE_SUBMITTED, true, inTenMinutes());
        insert("process-2", second, ReservationStatus.PHONE_SUBMITTED, true, inTenMinutes().plusSeconds(1));
        LISTINGS.put(first.toString(), list(FakeCatedraServer.appointment("process-1", first.toString(), "CONFIRMED", 8251L,
                CONFIRMED_AT, null, null)));
        LISTINGS.put(second.toString(), list(FakeCatedraServer.appointment("process-2", second.toString(), "CONFIRMED", 8252L,
                CONFIRMED_AT, null, null)));
        database.failUpdatesWhen(process -> process.getReservationProcessId().equals("process-2"));

        awaitStatus("process-1", ReservationStatus.CONFIRMED);
        await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains("Reservation process process-2 could not be reconciled"));
        assertThat(status("process-2")).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(marked("process-2")).isTrue();

        database.failUpdatesWhen(process -> false);
        awaitStatus("process-2", ReservationStatus.CONFIRMED);
    }

    // Mientras la cátedra tarda, no empieza otra reconciliación, y el barrido sigue cerrando lo vencido
    @Test
    void aSlowCatedraNeverRunsTwoReconciliationsAndDoesNotDelayTheOverdueSweep() {
        Long slow = newUser("maria.gomez");
        insert("process-1", slow, ReservationStatus.PHONE_SUBMITTED, true, inTenMinutes());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger answered = new AtomicInteger();
        CATEDRA.respondToListings(patient -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(INTERVAL.multipliedBy(4));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
                answered.incrementAndGet();
            }
            return list(FakeCatedraServer.appointment("process-1", patient, "PHONE_PENDING", 1664L, null, null, null));
        });
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> inFlight.get() == 1);
            insert("process-2", newUser("pedro.perez"), ReservationStatus.HELD, false, Instant.now().minus(1, ChronoUnit.HOURS));

            awaitStatus("process-2", ReservationStatus.EXPIRED);
            assertThat(inFlight.get()).isEqualTo(1);

            await().atMost(Duration.ofSeconds(20)).until(() -> answered.get() >= 3);
            assertThat(maxInFlight.get()).isEqualTo(1);
        } finally {
            CATEDRA.respondToListings(patient -> LISTINGS.getOrDefault(patient, FakeHttpServer.Response.of(200, "[]")));
        }
    }

    private long listingsOf(Long owner) {
        return CATEDRA.listings().stream().filter(request -> owner.toString().equals(FakeCatedraServer.patientOf(request))).count();
    }

    private void awaitStatus(String reservationProcessId, ReservationStatus expected) {
        await().atMost(Duration.ofSeconds(15)).until(() -> status(reservationProcessId) == expected);
    }

    private ReservationStatus status(String reservationProcessId) {
        return ReservationStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM reservation_processes WHERE reservation_process_id = ?", String.class, reservationProcessId));
    }

    private Boolean marked(String reservationProcessId) {
        return jdbcTemplate.queryForObject(
                "SELECT needs_reconciliation FROM reservation_processes WHERE reservation_process_id = ?", Boolean.class, reservationProcessId);
    }

    private Map<String, Object> row(String reservationProcessId) {
        return jdbcTemplate.queryForMap("SELECT * FROM reservation_processes WHERE reservation_process_id = ?", reservationProcessId);
    }

    private Long newUser(String login) {
        Instant now = Instant.now();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated, created_date, last_modified_date)
                VALUES (?, 'hash', 'María', 'Gómez', ?, 'es', true, ?, ?) RETURNING id
                """, Long.class, login, login + "@example.com", Timestamp.from(now), Timestamp.from(now));
    }

    // Con un id elegido: el de las reservas grabadas
    private Long newUser(Long id, String login) {
        Instant now = Instant.now();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (id, login, password_hash, first_name, last_name, email, lang_key, activated, created_date, last_modified_date)
                VALUES (?, ?, 'hash', 'María', 'Gómez', ?, 'es', true, ?, ?) RETURNING id
                """, Long.class, id, login, login + "@example.com", Timestamp.from(now), Timestamp.from(now));
    }

    private void insert(String reservationProcessId, Long owner, ReservationStatus status, boolean marked, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    professional_first_name, professional_last_name, professional_category_name, appointment_date,
                    start_time, status, expires_at, needs_reconciliation, reservation_id, confirmed_at)
                VALUES (?, ?, ?, 201, 'Ana', 'Gomez', 'Clinica medica', ?, ?, ?, ?, ?, ?, ?)
                """, reservationProcessId, "hold-" + reservationProcessId, owner, Date.valueOf(DATE),
                Time.valueOf(LocalTime.of(10, 30)), status.name(), Timestamp.from(expiresAt), marked,
                status == ReservationStatus.CONFIRMED ? 8251L : null,
                status == ReservationStatus.CONFIRMED ? Timestamp.from(CONFIRMED_AT) : null);
    }

    private static Instant inTenMinutes() {
        return Instant.now().plus(10, ChronoUnit.MINUTES);
    }

    private static FakeHttpServer.Response list(String... appointments) {
        return FakeHttpServer.Response.of(200, "[" + String.join(",", appointments) + "]");
    }

    private static String recording(String name) {
        try (InputStream input = ReconciliationIntegrationTest.class.getResourceAsStream("/catedra/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
