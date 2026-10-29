package turnos.availability;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa en un puerto real, con Postgres real y catálogo y cátedra simulados:
// registra con T01, inicia sesión con T02 y consulta los turnos libres con ese token
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class FreeSlotsIntegrationTest {

    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();
    private static final String OCCUPANCIES_PATH = "/api/appointment-occupancies";
    // Lunes de 12:00 a 15:00 cada 30 minutos: seis turnos
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
    // El próximo lunes, nunca hoy: ningún turno comenzó
    private static final LocalDate MONDAY = LocalDate.now(ZoneId.of("America/Argentina/Mendoza")).with(TemporalAdjusters.next(DayOfWeek.MONDAY));

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("TURNOS_CATALOG_BASE_URL", CATALOG::baseUrl);
        registry.add("TURNOS_CATALOG_INITIAL_DELAY", () -> "1ms");
        registry.add("TURNOS_CATEDRA_OPERATIONS_INITIAL_DELAY", () -> "1ms");
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

    private String userToken;

    @BeforeEach
    void registerAndLogIn() {
        await().atMost(Duration.ofSeconds(10)).until(() -> technicalSessionRepository.get().isEnabled());
        jdbcTemplate.update("DELETE FROM user_authorities");
        jdbcTemplate.update("DELETE FROM users");
        restTestClient.post().uri("/api/register").contentType(MediaType.APPLICATION_JSON).body(REGISTER_BODY)
                .exchange().expectStatus().isCreated();
        String body = restTestClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON).body(CREDENTIALS)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        userToken = JsonPath.read(body, "$.id_token");
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(200, PROFESSIONAL));
    }

    @Test
    void aLoggedInUserGetsTheFreeSlotsAndBothServicesReceiveTheRightToken() {
        CATEDRA.http().respond(OCCUPANCIES_PATH, FakeHttpServer.Response.of(200, recording("occupancies-200-empty.json")));

        String body = freeSlots(201).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<List<String>>read(body, "$.slots[*].startTime"))
                .containsExactly("12:00", "12:30", "13:00", "13:30", "14:00", "14:30");
        assertThat(JsonPath.<Integer>read(body, "$.slots[0].professionalId")).isEqualTo(201);
        assertThat(JsonPath.<String>read(body, "$.slots[0].date")).isEqualTo(MONDAY.toString());
        assertThat(JsonPath.<Object>read(body, "$.emptyReason")).isNull();
        assertThat(JsonPath.<Boolean>read(body, "$.stale")).isFalse();
        assertThat(JsonPath.<String>read(body, "$.dataAsOf")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        FakeHttpServer.ReceivedRequest catalogRequest = CATALOG.receivedRequests("/api/professionals/201").getLast();
        assertThat(catalogRequest.header("Authorization")).isEqualTo("Bearer " + userToken);
        FakeHttpServer.ReceivedRequest catedraRequest = CATEDRA.http().receivedRequests(OCCUPANCIES_PATH).getLast();
        assertThat(catedraRequest.query()).isEqualTo("professionalId=201&from=" + MONDAY + "&to=" + MONDAY);
        assertThat(catedraRequest.header("Authorization")).isEqualTo("Bearer " + FakeCatedraServer.TOKEN);
    }

    @Test
    void takenSlotsAreNotOffered() {
        CATEDRA.http().respond(OCCUPANCIES_PATH, FakeHttpServer.Response.of(200, occupancies("12:00:00", "CONFIRMED", "13:30", "HELD")));

        String body = freeSlots(202).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<List<String>>read(body, "$.slots[*].startTime")).containsExactly("12:30", "13:00", "14:00", "14:30");
    }

    @Test
    void whenTheCatedraStopsAnsweringTheLastAnswerIsUsedAsStale(CapturedOutput output) {
        CATEDRA.http().respond(OCCUPANCIES_PATH, FakeHttpServer.Response.of(200, occupancies("12:00:00", "CONFIRMED")));
        String live = freeSlots(203).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        CATEDRA.http().respond(OCCUPANCIES_PATH, FakeHttpServer.Response.of(503, "{}"));

        String cached = freeSlots(203).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<Boolean>read(cached, "$.stale")).isTrue();
        assertThat(JsonPath.<String>read(cached, "$.dataAsOf")).isEqualTo(JsonPath.<String>read(live, "$.dataAsOf"));
        assertThat(JsonPath.<List<String>>read(cached, "$.slots[*].startTime")).doesNotContain("12:00").hasSize(5);
        assertThat(output.getAll()).contains("Catedra occupancies request for professional 203 attempt 3/3 failed");
    }

    @Test
    void withoutACachedAnswerAnUnavailableCatedraIsReported() {
        CATEDRA.http().respond(OCCUPANCIES_PATH, FakeHttpServer.Response.of(503, "{}"));

        freeSlots(204).expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("CENTRAL_UNAVAILABLE");
    }

    @Test
    void anUnavailableCatalogIsReportedWithoutAskingTheCatedra(CapturedOutput output) {
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(503, "{\"status\":503,\"code\":\"CATALOG_NOT_INITIALIZED\"}"));
        int catedraRequests = CATEDRA.http().receivedRequests(OCCUPANCIES_PATH).size();

        freeSlots(205).expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("CATALOG_UNAVAILABLE");

        assertThat(CATALOG.receivedRequests("/api/professionals/205")).hasSize(3);
        assertThat(CATEDRA.http().receivedRequests(OCCUPANCIES_PATH)).hasSize(catedraRequests);
        assertThat(output.getAll()).contains("Catalog request for professional 205 attempt 3/3 failed");
    }

    @Test
    void theDatabaseIsNotChangedAndNoTokenReachesTheOutput(CapturedOutput output) {
        Map<String, Integer> before = rowsPerTable();
        CATEDRA.http().respond(OCCUPANCIES_PATH, FakeHttpServer.Response.of(200, recording("occupancies-200-empty.json")));
        freeSlots(206).expectStatus().isOk();
        CATEDRA.http().respond(OCCUPANCIES_PATH, FakeHttpServer.Response.of(503, "{}"));
        freeSlots(206).expectStatus().isOk();
        freeSlots(207).expectStatus().isEqualTo(503);
        CATEDRA.http().respond(OCCUPANCIES_PATH, FakeHttpServer.Response.of(400, recording("occupancies-400-invalid-date-range.json")));
        freeSlots(208).expectStatus().isEqualTo(500).expectBody().jsonPath("$.code").isEqualTo("INTERNAL_ERROR");

        assertThat(rowsPerTable()).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain(userToken).doesNotContain(FakeCatedraServer.TOKEN);
    }

    private RestTestClient.ResponseSpec freeSlots(long professionalId) {
        return restTestClient.get().uri("/api/free-slots?professionalId={id}&date={date}", professionalId, MONDAY)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange();
    }

    private Map<String, Integer> rowsPerTable() {
        Map<String, Integer> rows = new LinkedHashMap<>();
        for (String table : jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name", String.class)) {
            rows.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"" + table + "\"", Integer.class));
        }
        return rows;
    }

    // Pares de hora y estado, en el formato de la cátedra
    private static String occupancies(String... timesAndStatuses) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < timesAndStatuses.length; i += 2) {
            items.append(items.isEmpty() ? "" : ",").append("{\"date\":\"").append(MONDAY).append("\",\"startTime\":\"")
                    .append(timesAndStatuses[i]).append("\",\"status\":\"").append(timesAndStatuses[i + 1]).append("\",\"expiresAt\":null}");
        }
        return "{\"professionalId\":201,\"from\":\"" + MONDAY + "\",\"to\":\"" + MONDAY + "\",\"occupancies\":[" + items + "]}";
    }

    private static String recording(String name) {
        try (InputStream input = FreeSlotsIntegrationTest.class.getResourceAsStream("/catedra/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
