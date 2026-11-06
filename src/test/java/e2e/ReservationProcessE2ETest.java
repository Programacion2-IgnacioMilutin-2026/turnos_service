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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Contra la cátedra real, con la cuenta técnica del .env local: solo con ./mvnw test -Pe2e. Sin credenciales se saltea.
// Crea un hold real en un turno libre, lo confirma y, si el .env define el consumer group de los E2E, espera el pedido
// de teléfono que la cátedra publica por Kafka, informa un teléfono y espera la reserva confirmada para cancelarla con
// el servicio: una reserva confirmada no vence sola. Al terminar siempre intenta cancelar directamente en la cátedra,
// para no dejar nada tomado, también si este servicio no llegó a enterarse de una confirmación.
// Si la cátedra no lo permite, lo avisa sin fallar: un proceso sin confirmar vence solo.
// El otro recorrido bloquea un turno, lo abandona y bloquea otro: no depende de Kafka, y sus dos holds vencen solos
@Tag("e2e")
@EnabledIf(value = "e2e.E2ECredentials#available", disabledReason = "No catedra credentials in the local .env: E2E tests skipped")
@SpringBootTest(classes = TurnosServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ReservationProcessE2ETest {

    private static final String TEST_PHONE = "+5492610000000";
    private static final Duration RESULT_WAIT = Duration.ofSeconds(60);
    private static final Duration RESULT_POLL = Duration.ofSeconds(2);
    // Responde con el profesional que se elige del snapshot de la cátedra (E2EProfessional)
    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    private static final String REGISTER_BODY = """
            {"login":"%s","password":"secreta123","firstName":"Prueba","lastName":"E2E",
             "email":"%s@example.com","langKey":"es"}
            """;

    @DynamicPropertySource
    static void simulatedCatalog(DynamicPropertyRegistry registry) {
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
    private E2EProfessional professional;

    @Test
    void aRegisteredUserBlocksAFreeSlotAndConfirmsItWithTheRealCatedra(CapturedOutput output) {
        await().atMost(Duration.ofSeconds(30)).until(() -> technicalSessionRepository.get().isEnabled());
        String userToken = registerAndLogIn("e2e.reservas");

        // El turno se elige entre los libres: un hold de una corrida anterior aparece como ocupado
        E2EProfessional.Choice choice = choose(userToken, 1);
        LocalDate date = choice.date();
        String slot = choice.freeSlots().getFirst();

        String body = block(userToken, date, slot).expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        reservationProcessId = JsonPath.read(body, "$.reservationProcessId");

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("HELD");
        assertThat(JsonPath.<String>read(body, "$.startTime")).isEqualTo(slot);
        assertThat(JsonPath.<String>read(body, "$.professionalCategoryName")).isEqualTo(professional.categoryName());
        expiresAt = Instant.parse(JsonPath.read(body, "$.expiresAt"));
        assertThat(expiresAt).isAfter(Instant.now());
        System.out.println("E2E hold " + reservationProcessId + " for professional " + professional.id() + " on " + date + " at " + slot
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

            // Se publica en el topic propio de esta cuenta. El teléfono es de mentira, pero con un formato válido
            String submitted = restTestClient.post().uri("/api/reservation-processes/{id}/phone", reservationProcessId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"phoneNumber\":\"" + TEST_PHONE + "\"}")
                    .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
            assertThat(JsonPath.<String>read(submitted, "$.status")).isEqualTo("PHONE_SUBMITTED");
            assertThat(submitted).doesNotContain(TEST_PHONE);
            assertThat(output.getAll()).doesNotContain(TEST_PHONE);
            System.out.println("E2E phone of " + reservationProcessId + " published");

            // La app sigue el resultado con esta misma consulta
            await().alias("the Catedra answers the phone of " + reservationProcessId + " and the result is saved")
                    .atMost(RESULT_WAIT).pollInterval(RESULT_POLL).until(() -> !"PHONE_SUBMITTED".equals(status(query(userToken))));
            String result = query(userToken);
            assertThat(status(result)).as("result of the phone of " + reservationProcessId + ": " + result).isEqualTo("CONFIRMED");
            assertThat(JsonPath.<Object>read(result, "$.reservationId")).isNotNull();
            assertThat(JsonPath.<String>read(result, "$.confirmedAt")).isNotNull();
            assertThat(JsonPath.<String>read(result, "$.expiresAt")).isNull();
            System.out.println("E2E reservation " + JsonPath.<Object>read(result, "$.reservationId") + " of "
                    + reservationProcessId + " confirmed");

            long eventsBeforeCancelling = processedEvents();
            String cancelled = cancelWithTheService(userToken, reservationProcessId).expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();
            assertThat(status(cancelled)).isEqualTo("CANCELLED");
            assertThat(JsonPath.<Object>read(cancelled, "$.reservationId")).isNotNull();
            assertThat(JsonPath.<String>read(cancelled, "$.cancelledAt")).isNotNull();
            System.out.println("E2E reservation of " + reservationProcessId + " cancelled with the service");

            // La cátedra también avisa la cancelación por Kafka: el proceso ya estaba cancelado, y el evento solo se registra
            await().alias("the cancellation event of " + reservationProcessId + " is processed")
                    .atMost(RESULT_WAIT).pollInterval(RESULT_POLL).until(() -> processedEvents() > eventsBeforeCancelling);
        }
        assertThat(output.getAll()).doesNotContain(userToken).doesNotContain(technicalSessionRepository.get().token());
    }

    // Sus dos holds vencen solos: la cátedra no permite cancelar un proceso sin confirmar, así que no se intenta
    @Test
    void aUserAbandonsAHeldProcessAndBlocksAnotherFreeSlot() {
        await().atMost(Duration.ofSeconds(30)).until(() -> technicalSessionRepository.get().isEnabled());
        String userToken = registerAndLogIn("e2e.abandono");
        E2EProfessional.Choice choice = choose(userToken, 2);
        LocalDate date = choice.date();
        List<String> startTimes = choice.freeSlots();
        String abandonedSlot = startTimes.getFirst();
        String otherSlot = startTimes.get(1);

        String held = block(userToken, date, abandonedSlot).expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        String abandoned = JsonPath.read(held, "$.reservationProcessId");
        System.out.println("E2E hold " + abandoned + " on " + date + " at " + abandonedSlot);
        block(userToken, date, otherSlot).expectStatus().isEqualTo(409);

        String cancelled = cancelWithTheService(userToken, abandoned).expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(status(cancelled)).isEqualTo("CANCELLED");
        System.out.println("E2E: " + abandoned + " abandoned without telling the Catedra");

        // Lo que la decisión de abandonar supone: la cátedra acepta otro hold con el primero todavía activo
        String second = block(userToken, date, otherSlot).expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        assertThat(status(second)).isEqualTo("HELD");
        System.out.println("E2E second hold " + JsonPath.read(second, "$.reservationProcessId") + " at " + otherSlot
                + " accepted while the first one is still active");

        // Y el turno abandonado sigue tomado en la cátedra hasta que venza
        String otherUserToken = registerAndLogIn("e2e.otro");
        String rejected = block(otherUserToken, date, abandonedSlot).expectStatus().isEqualTo(409)
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(JsonPath.<String>read(rejected, "$.code")).isEqualTo("SLOT_NOT_AVAILABLE");
        System.out.println("E2E: the abandoned slot " + abandonedSlot + " is still taken until its hold expires");
    }

    private String registerAndLogIn(String username) {
        restTestClient.post().uri("/api/register").contentType(MediaType.APPLICATION_JSON).body(REGISTER_BODY.formatted(username, username))
                .exchange().expectStatus().isCreated();
        String login = restTestClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"secreta123\"}")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        return JsonPath.read(login, "$.id_token");
    }

    // Un profesional habilitado de la cátedra y el primer día con los turnos libres que el recorrido necesita
    private E2EProfessional.Choice choose(String userToken, int freeSlotsNeeded) {
        List<E2EProfessional> professionals = E2EProfessional.enabledInTheCatedra(
                environment.getProperty("turnos.catedra.base-url"), technicalSessionRepository.get().token());
        E2EProfessional.Choice choice = E2EProfessional.choose(professionals, CATALOG, freeSlotsNeeded,
                (professionalId, date) -> freeSlots(userToken, professionalId, date));
        professional = choice.professional();
        return choice;
    }

    private List<String> freeSlots(String userToken, long professionalId, LocalDate date) {
        String body = restTestClient.get().uri("/api/free-slots?professionalId={id}&date={date}", professionalId, date)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        return JsonPath.read(body, "$.slots[*].startTime");
    }

    private RestTestClient.ResponseSpec block(String userToken, LocalDate date, String startTime) {
        return restTestClient.post().uri("/api/reservation-processes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).contentType(MediaType.APPLICATION_JSON)
                .body("{\"professionalId\":" + professional.id() + ",\"date\":\"" + date + "\",\"startTime\":\"" + startTime + "\"}")
                .exchange();
    }

    private RestTestClient.ResponseSpec cancelWithTheService(String userToken, String process) {
        return restTestClient.post().uri("/api/reservation-processes/{id}/cancel", process)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange();
    }

    private long processedEvents() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_events", Long.class);
    }

    private String query(String userToken) {
        return restTestClient.get().uri("/api/reservation-processes/{id}", reservationProcessId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    private static String status(String process) {
        return JsonPath.read(process, "$.status");
    }

    private String requestEventId() {
        return jdbcTemplate.queryForObject(
                "SELECT request_event_id FROM reservation_processes WHERE reservation_process_id = ?", String.class,
                reservationProcessId);
    }

    // Deshace en la cátedra real lo que creó el test. Si ya estaba cancelado, la cátedra responde lo mismo
    @AfterEach
    void cancelTheProcess() {
        if (reservationProcessId == null) {
            return;
        }
        int status = cancelInTheCatedra();
        if (status == 200) {
            System.out.println("E2E: process " + reservationProcessId + " cancelled");
        } else {
            System.out.println("E2E: process " + reservationProcessId + " could not be cancelled (HTTP " + status
                    + "); it expires at " + expiresAt + " unless the Catedra confirms it: then it has to be cancelled by hand");
        }
    }

    // Una llamada directa, como limpieza: también sirve si este servicio no se enteró de una confirmación. Nunca muestra
    // la URL ni el token técnico
    private int cancelInTheCatedra() {
        ResponseEntity<String> response = RestClient.create().post()
                .uri(environment.getProperty("turnos.catedra.base-url") + "/api/appointments/{id}/cancel", reservationProcessId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + technicalSessionRepository.get().token())
                .retrieve()
                .onStatus(status -> true, (request, httpResponse) -> {
                    // la respuesta se revisa abajo, sin lanzar una excepción
                })
                .toEntity(String.class);
        return response.getStatusCode().value();
    }
}
