package turnos.reservation;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import turnos.FakeHttpServer;
import turnos.TestcontainersConfiguration;
import turnos.catedra.FakeCatedraServer;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationStatus;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa en un puerto real, con Postgres real y catálogo y cátedra simulados. Los procesos se escriben
// en la base como estado de partida. El tiempo máximo convierte en una falla un listado que espere el candado
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(value = 45, unit = TimeUnit.SECONDS)
class ReservationProcessListIntegrationTest {

    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();
    private static final String PATH = "/api/reservation-processes";
    private static final String PHONE = "+5492615551234";
    private static final Duration MARGIN = Duration.ofSeconds(60);
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
    private static final LocalTime TIME = LocalTime.of(10, 30);

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("TURNOS_CATALOG_BASE_URL", CATALOG::baseUrl);
        registry.add("TURNOS_ATTENTION_ZONE", ZONE::getId);
        registry.add("TURNOS_RESERVATION_SAFETY_MARGIN", () -> MARGIN.getSeconds() + "s");
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "cuenta-tecnica-de-test");
        registry.add("turnos.catedra.password", () -> "clave-de-test");
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
    private UserOperationLock userOperationLock;

    private String token;
    private Long userId;
    private Long otherUserId;
    private LocalDate today;

    @BeforeEach
    void registerAndLogIn() {
        await().atMost(Duration.ofSeconds(10)).until(() -> technicalSessionRepository.get().isEnabled());
        cleanDatabase();
        token = registerAndAuthenticate("maria.gomez");
        registerAndAuthenticate("pedro.perez");
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE login = 'maria.gomez'", Long.class);
        otherUserId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE login = 'pedro.perez'", Long.class);
        today = LocalDate.now(ZONE);
    }

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM reservation_processes");
        jdbcTemplate.update("DELETE FROM user_authorities");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void withoutParametersItAnswersTheFirstPageWithItsDefaults() {
        insert("process-1", userId, ReservationStatus.CONFIRMED, today.plusDays(1), TIME, future());

        Map<String, Object> page = JsonPath.read(list(""), "$");

        assertThat(page).containsOnlyKeys("items", "page", "size", "totalItems");
        assertThat(page).containsEntry("page", 0).containsEntry("size", 10).containsEntry("totalItems", 1);
    }

    @Test
    void emptyParametersOnARealRequestAnswerTheSameAsNoParameters() {
        insert("process-1", userId, ReservationStatus.CONFIRMED, today.plusDays(1), TIME, future());
        insert("process-2", userId, ReservationStatus.CANCELLED, today.minusDays(1), TIME, future());

        assertThat(list("?status=&page=&size=")).isEqualTo(list(""));
    }

    @ParameterizedTest
    @CsvSource({"status, unknown", "status, confirmed", "status, EXPIRED", "status, INVALID", "page, -1", "size, 0", "size, 101", "page, abc"})
    void anInvalidParameterIsAValidationErrorWithItsField(String field, String value) {
        String body = restTestClient.get().uri(PATH + "?" + field + "=" + value)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("VALIDATION_ERROR");
        assertThat(JsonPath.<String>read(body, "$.fieldErrors[0].field")).isEqualTo(field);
    }

    @Test
    void withoutATokenItAnswersUnauthorized() {
        restTestClient.get().uri(PATH).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void onlyTheProcessesOfTheOwnerOfTheTokenAreListedAndCounted() {
        insert("mine", userId, ReservationStatus.CONFIRMED, today.plusDays(1), TIME, future());
        insert("foreign", otherUserId, ReservationStatus.CONFIRMED, today.plusDays(1), TIME, future());

        String body = list("");

        assertThat(ids(body)).containsExactly("mine");
        assertThat(JsonPath.<Integer>read(body, "$.totalItems")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED", "CONFIRMED", "CANCELLED"})
    void theFilterKeepsOnlyThatStatus(ReservationStatus status) {
        insert("open", userId, status.isOpen() ? status : ReservationStatus.HELD, today.plusDays(1), TIME, future());
        insert("confirmed", userId, ReservationStatus.CONFIRMED, today.plusDays(2), TIME, future());
        insert("cancelled", userId, ReservationStatus.CANCELLED, today.plusDays(3), TIME, future());

        String body = list("?status=" + status);

        assertThat(JsonPath.<List<String>>read(body, "$.items[*].status")).containsOnly(status.name());
        assertThat(JsonPath.<Integer>read(body, "$.totalItems")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void anOverdueProcessWaitingForTheUserIsNotListedAndStaysAsItWas(ReservationStatus status) {
        insert("overdue", userId, status, today.plusDays(1), TIME, overdue());
        insert("confirmed", userId, ReservationStatus.CONFIRMED, today.plusDays(2), TIME, future());
        Map<String, Object> before = stored("overdue");

        String body = list("");

        assertThat(ids(body)).containsExactly("confirmed");
        assertThat(JsonPath.<Integer>read(body, "$.totalItems")).isEqualTo(1);
        assertThat(ids(list("?status=" + status))).isEmpty();
        assertThat(stored("overdue")).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void insideTheMarginAProcessWaitingForTheUserIsStillListed(ReservationStatus status) {
        insert("inside-the-margin", userId, status, today.plusDays(1), TIME, Instant.now().minusSeconds(5));

        assertThat(ids(list(""))).containsExactly("inside-the-margin");
    }

    @Test
    void anOverdueProcessWithThePhoneSubmittedIsListedAndNotMarked() {
        insert("submitted", userId, ReservationStatus.PHONE_SUBMITTED, today.plusDays(1), TIME, overdue());

        String body = list("");

        assertThat(ids(body)).containsExactly("submitted");
        assertThat(JsonPath.<String>read(body, "$.items[0].status")).isEqualTo("PHONE_SUBMITTED");
        assertThat(JsonPath.<String>read(body, "$.items[0].expiresAt")).isNotNull();
        assertThat(stored("submitted")).containsEntry("needs_reconciliation", false).containsEntry("status", "PHONE_SUBMITTED");
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"EXPIRED", "INVALID"})
    void anAttemptThatCameToNothingIsNotListed(ReservationStatus status) {
        insert("attempt", userId, status, today.plusDays(1), TIME, future());

        String body = list("");

        assertThat(ids(body)).isEmpty();
        assertThat(JsonPath.<Integer>read(body, "$.totalItems")).isZero();
    }

    @Test
    void aProcessCancelledBeforeConfirmationIsNotListedNotEvenFilteringByCancelled() {
        insert("never-confirmed", userId, ReservationStatus.CANCELLED, today.plusDays(1), TIME, future());
        jdbcTemplate.update("UPDATE reservation_processes SET confirmed_at = NULL");
        insert("confirmed-first", userId, ReservationStatus.CANCELLED, today.plusDays(2), TIME, future());

        assertThat(ids(list(""))).containsExactly("confirmed-first");
        assertThat(ids(list("?status=CANCELLED"))).containsExactly("confirmed-first");
    }

    @Test
    void walkingThePagesFollowsTheOrderWithoutRepeatsOrGaps() {
        insert("past-old", userId, ReservationStatus.CANCELLED, today.minusDays(30), TIME, future());
        insert("far", userId, ReservationStatus.CONFIRMED, today.plusDays(30), TIME, future());
        insert("started-today", userId, ReservationStatus.CONFIRMED, today, LocalTime.MIN, future());
        insert("past-recent", userId, ReservationStatus.CONFIRMED, today.minusDays(1), LocalTime.of(18, 0), future());
        insert("tied-b", userId, ReservationStatus.CONFIRMED, today.plusDays(2), TIME, future());
        insert("later-today", userId, ReservationStatus.CONFIRMED, today, LocalTime.of(23, 59, 59), future());
        insert("tied-a", userId, ReservationStatus.CANCELLED, today.plusDays(2), TIME, future());
        insert("open", userId, ReservationStatus.WAITING_FOR_PHONE, today.minusDays(60), TIME, future());

        List<String> walked = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = list("?page=" + page + "&size=3");
            assertThat(JsonPath.<Integer>read(body, "$.page")).isEqualTo(page);
            assertThat(JsonPath.<Integer>read(body, "$.size")).isEqualTo(3);
            assertThat(JsonPath.<Integer>read(body, "$.totalItems")).isEqualTo(8);
            walked.addAll(ids(body));
        }

        assertThat(walked).containsExactly("open", "later-today", "tied-a", "tied-b", "far", "started-today", "past-recent", "past-old");
    }

    @Test
    void aUserWithoutReservationsGetsAnEmptyPage() {
        String body = list("");

        assertThat(ids(body)).isEmpty();
        assertThat(JsonPath.<Integer>read(body, "$.totalItems")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"1, 10", "2147483647, 100"})
    void aPageBeyondTheLastIsEmptyWithTheRealTotal(int page, int size) {
        insert("process-1", userId, ReservationStatus.CONFIRMED, today.plusDays(1), TIME, future());

        String body = list("?page=" + page + "&size=" + size);

        assertThat(ids(body)).isEmpty();
        assertThat(JsonPath.<Integer>read(body, "$.totalItems")).isEqualTo(1);
    }

    @Test
    void eachItemHasItsFieldsAccordingToItsStatus() {
        Instant expiresAt = future();
        insert("open", userId, ReservationStatus.WAITING_FOR_PHONE, today.plusDays(1), TIME, expiresAt);
        jdbcTemplate.update("UPDATE reservation_processes SET rejection_reason = 'INVALID_PHONE_NUMBER', confirmed_at = NULL");
        insert("confirmed", userId, ReservationStatus.CONFIRMED, today.plusDays(2), TIME, expiresAt);
        jdbcTemplate.update("UPDATE reservation_processes SET reservation_id = 8251, confirmed_at = ? WHERE reservation_process_id = 'confirmed'",
                Timestamp.from(Instant.parse("2026-10-27T04:36:01.987654Z")));
        insert("cancelled", userId, ReservationStatus.CANCELLED, today.plusDays(3), TIME, expiresAt);
        jdbcTemplate.update("UPDATE reservation_processes SET reservation_id = 8252, cancelled_at = ?, "
                        + "cancellation_reason = 'Cambio solicitado por el usuario' WHERE reservation_process_id = 'cancelled'",
                Timestamp.from(Instant.parse("2026-10-28T15:10:00.5Z")));

        List<Map<String, Object>> items = JsonPath.read(list(""), "$.items");

        assertThat(items.get(0)).containsEntry("reservationProcessId", "open").containsEntry("professionalFirstName", "Ana")
                .containsEntry("professionalCategoryName", "Clinica medica").containsEntry("date", today.plusDays(1).toString())
                .containsEntry("startTime", "10:30").containsEntry("rejectionReason", "INVALID_PHONE_NUMBER")
                .containsEntry("expiresAt", expiresAt.truncatedTo(ChronoUnit.SECONDS).toString()).containsEntry("reservationId", null);
        assertThat(items.get(1)).containsEntry("reservationProcessId", "confirmed").containsEntry("reservationId", 8251)
                .containsEntry("confirmedAt", "2026-10-27T04:36:01Z").containsEntry("expiresAt", null).containsEntry("cancelledAt", null);
        assertThat(items.get(2)).containsEntry("reservationProcessId", "cancelled").containsEntry("reservationId", 8252)
                .containsEntry("cancelledAt", "2026-10-28T15:10:00Z").containsEntry("cancellationReason", "Cambio solicitado por el usuario")
                .containsEntry("expiresAt", null);
    }

    @Test
    void itAnswersWhileAnotherOperationOfTheUserHoldsTheLock() {
        insert("process-1", userId, ReservationStatus.CONFIRMED, today.plusDays(1), TIME, future());

        String body = userOperationLock.exclusivelyFor(userId, () -> list(""));

        assertThat(ids(body)).containsExactly("process-1");
    }

    @Test
    void listingChangesNoRowAndCallsNothingOutside() {
        insert("overdue", userId, ReservationStatus.WAITING_FOR_PHONE, today.plusDays(1), TIME, overdue());
        insert("confirmed", userId, ReservationStatus.CONFIRMED, today.plusDays(2), TIME, overdue());
        List<Map<String, Object>> before = allRows();
        int catedraRequests = CATEDRA.receivedRequests().size();
        int catalogRequests = CATALOG.receivedRequests().size();

        list("");
        list("?status=CONFIRMED");

        assertThat(allRows()).isEqualTo(before);
        assertThat(CATEDRA.receivedRequests()).hasSize(catedraRequests);
        assertThat(CATALOG.receivedRequests()).hasSize(catalogRequests);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED", "CONFIRMED", "CANCELLED"})
    void noItemExposesTheHoldTheMarkThePhoneRequestOrThePhone(ReservationStatus status, CapturedOutput output) {
        insert("process-1", userId, status, today.plusDays(1), TIME, future());
        jdbcTemplate.update("UPDATE reservation_processes SET phone_number = ?, request_event_id = 'request-event-1', needs_reconciliation = TRUE", PHONE);

        String body = list("");

        Map<String, Object> item = JsonPath.read(body, "$.items[0]");
        assertThat(item).containsOnlyKeys("reservationProcessId", "status", "professionalId", "professionalFirstName",
                "professionalLastName", "professionalCategoryName", "date", "startTime", "expiresAt", "rejectionReason",
                "reservationId", "confirmedAt", "cancelledAt", "cancellationReason");
        assertThat(body).doesNotContain("hold-").doesNotContain("request-event-1").doesNotContain("5492615551234");
        assertThat(output.getAll()).doesNotContain("5492615551234");
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

    // Con la fecha de confirmación puesta, que las canceladas necesitan para listarse
    private void insert(String reservationProcessId, Long owner, ReservationStatus status, LocalDate date, LocalTime startTime, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                    professional_first_name, professional_last_name, professional_category_name, appointment_date,
                    start_time, status, expires_at, confirmed_at)
                VALUES (?, ?, ?, 201, 'Ana', 'Gomez', 'Clinica medica', ?, ?, ?, ?, ?)
                """, reservationProcessId, "hold-" + reservationProcessId, owner, Date.valueOf(date), Time.valueOf(startTime),
                status.name(), Timestamp.from(expiresAt), Timestamp.from(Instant.parse("2026-10-27T04:36:01Z")));
    }

    private Map<String, Object> stored(String reservationProcessId) {
        return jdbcTemplate.queryForMap("SELECT * FROM reservation_processes WHERE reservation_process_id = ?", reservationProcessId);
    }

    private List<Map<String, Object>> allRows() {
        return jdbcTemplate.queryForList("SELECT * FROM reservation_processes ORDER BY reservation_process_id");
    }

    private String list(String parameters) {
        return restTestClient.get().uri(PATH + parameters).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    private static List<String> ids(String body) {
        return JsonPath.read(body, "$.items[*].reservationProcessId");
    }

    private static Instant future() {
        return Instant.now().plus(10, ChronoUnit.MINUTES);
    }

    private static Instant overdue() {
        return Instant.now().minus(MARGIN).minus(10, ChronoUnit.MINUTES);
    }
}
