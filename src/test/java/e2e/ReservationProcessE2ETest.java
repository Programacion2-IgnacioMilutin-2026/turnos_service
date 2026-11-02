package e2e;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.RestClient;
import turnos.FakeHttpServer;
import turnos.TestcontainersConfiguration;
import turnos.TurnosServiceApplication;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Contra la cátedra real, con la cuenta técnica del .env local: solo con ./mvnw test -Pe2e. Sin credenciales se saltea.
// Crea un hold real en un turno libre, lo confirma y, si el .env define el consumer group de los E2E, espera el pedido
// de teléfono que la cátedra publica por Kafka. Al terminar intenta cancelarlo directamente en la cátedra, para no
// dejarlo tomado.
// Si la cátedra no lo permite, lo avisa sin fallar: lo que se prueba es la creación del hold, y el hold vence solo
@Tag("e2e")
@EnabledIf(value = "e2e.E2ECredentials#available", disabledReason = "No catedra credentials in the local .env: E2E tests skipped")
@SpringBootTest(classes = TurnosServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ReservationProcessE2ETest {

    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    // El profesional de referencia de la cátedra: lunes de 12:00 a 15:00 cada 30 minutos
    private static final String PROFESSIONAL = """
            {"id":201,"firstName":"Ana","lastName":"Gomez","enabled":true,"category":{"id":1,"name":"Clinica medica","enabled":true},
             "weeklySchedules":[
             {"dayOfWeek":"MONDAY","startTime":"12:00:00","endTime":"15:00:00","slotDurationMinutes":30,"enabled":true}]}
            """;
    private static final String REGISTER_BODY = """
            {"login":"e2e.reservas","password":"secreta123","firstName":"Prueba","lastName":"E2E",
             "email":"e2e.reservas@example.com","langKey":"es"}
            """;

    @DynamicPropertySource
    static void simulatedCatalog(DynamicPropertyRegistry registry) {
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(200, PROFESSIONAL));
        registry.add("TURNOS_CATALOG_BASE_URL", CATALOG::baseUrl);
        E2EKafka.configure(registry);
    }

    @AfterAll
    static void stopCatalog() {
        CATALOG.close();
    }

    @Autowired
    private RestTestClient restTestClient;
    @Autowired
    private TechnicalSessionRepository technicalSessionRepository;
    @Autowired
    private Environment environment;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String reservationProcessId;
    private Instant expiresAt;

    @Test
    void aRegisteredUserBlocksAFreeSlotAndConfirmsItWithTheRealCatedra(CapturedOutput output) {
        await().atMost(Duration.ofSeconds(30)).until(() -> technicalSessionRepository.get().isEnabled());
        restTestClient.post().uri("/api/register").contentType(MediaType.APPLICATION_JSON).body(REGISTER_BODY)
                .exchange().expectStatus().isCreated();
        String login = restTestClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                .body("{\"username\":\"e2e.reservas\",\"password\":\"secreta123\"}")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        String userToken = JsonPath.read(login, "$.id_token");
        LocalDate monday = LocalDate.now(ZoneId.of("America/Argentina/Mendoza")).with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        // El turno se elige entre los libres: un hold de una corrida anterior aparece como ocupado
        String freeSlots = restTestClient.get().uri("/api/free-slots?professionalId=201&date={date}", monday)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        List<String> startTimes = JsonPath.read(freeSlots, "$.slots[*].startTime");
        assertThat(startTimes).as("free slots of professional 201 on " + monday).isNotEmpty();
        String slot = startTimes.getFirst();

        String body = restTestClient.post().uri("/api/reservation-processes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).contentType(MediaType.APPLICATION_JSON)
                .body("{\"professionalId\":201,\"date\":\"" + monday + "\",\"startTime\":\"" + slot + "\"}")
                .exchange().expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        reservationProcessId = JsonPath.read(body, "$.reservationProcessId");

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("HELD");
        assertThat(JsonPath.<String>read(body, "$.startTime")).isEqualTo(slot);
        assertThat(JsonPath.<String>read(body, "$.professionalCategoryName")).isEqualTo("Clinica medica");
        expiresAt = Instant.parse(JsonPath.read(body, "$.expiresAt"));
        assertThat(expiresAt).isAfter(Instant.now());
        System.out.println("E2E hold " + reservationProcessId + " for professional 201 on " + monday + " at " + slot
                + ", expires at " + expiresAt);

        // Confirmar no extiende el vencimiento: la cátedra devuelve el mismo del hold
        String confirmed = restTestClient.post().uri("/api/reservation-processes/{id}/confirm", reservationProcessId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(JsonPath.<String>read(confirmed, "$.reservationProcessId")).isEqualTo(reservationProcessId);
        assertThat(JsonPath.<String>read(confirmed, "$.status")).isEqualTo("WAITING_FOR_PHONE");
        assertThat(JsonPath.<String>read(confirmed, "$.expiresAt")).isEqualTo(JsonPath.<String>read(body, "$.expiresAt"));
        System.out.println("E2E confirmation of " + reservationProcessId + ": WAITING_FOR_PHONE until " + expiresAt);

        // El pedido de teléfono llega por Kafka y queda guardado en el proceso, en la base de este test
        if (E2EKafka.consumerGroup().isEmpty()) {
            System.out.println("E2E: TURNOS_E2E_KAFKA_CONSUMER_GROUP is not set in the .env, the phone request is not checked");
        } else {
            await().alias("the Catedra publishes the phone request of " + reservationProcessId + " and it is saved")
                    .atMost(Duration.ofSeconds(30)).until(() -> requestEventId() != null);
            System.out.println("E2E phone request " + requestEventId() + " saved for " + reservationProcessId);
        }
        assertThat(output.getAll()).doesNotContain(userToken).doesNotContain(technicalSessionRepository.get().token());
    }

    private String requestEventId() {
        return jdbcTemplate.queryForObject(
                "SELECT request_event_id FROM reservation_processes WHERE reservation_process_id = ?", String.class,
                reservationProcessId);
    }

    // Deshace en la cátedra real lo que creó el test. Nunca muestra la URL ni el token técnico
    @AfterEach
    void cancelTheProcess() {
        if (reservationProcessId == null) {
            return;
        }
        ResponseEntity<String> response = RestClient.create().post()
                .uri(environment.getProperty("turnos.catedra.base-url") + "/api/appointments/{id}/cancel", reservationProcessId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + technicalSessionRepository.get().token())
                .retrieve()
                .onStatus(status -> true, (request, httpResponse) -> {
                    // la respuesta se revisa abajo, sin lanzar una excepción
                })
                .toEntity(String.class);
        int status = response.getStatusCode().value();
        if (status == 200) {
            System.out.println("E2E: process " + reservationProcessId + " cancelled");
        } else {
            System.out.println("E2E: process " + reservationProcessId + " could not be cancelled (HTTP " + status
                    + "); it expires at " + expiresAt);
        }
    }
}
