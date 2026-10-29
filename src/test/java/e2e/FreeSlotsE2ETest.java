package e2e;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import turnos.FakeHttpServer;
import turnos.TestcontainersConfiguration;
import turnos.TurnosServiceApplication;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Contra la cátedra real, con la cuenta técnica del .env local: solo con ./mvnw test -Pe2e.
// Sin credenciales se saltea. El catálogo es simulado mientras no exista el real; la base es de Testcontainers
@Tag("e2e")
@EnabledIf(value = "e2e.E2ECredentials#available", disabledReason = "No catedra credentials in the local .env: E2E tests skipped")
@SpringBootTest(classes = TurnosServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class FreeSlotsE2ETest {

    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    // El profesional de referencia de la cátedra: lunes de 12:00 a 15:00 cada 30 minutos
    private static final String PROFESSIONAL = """
            {"id":201,"firstName":"Ana","lastName":"Gomez","enabled":true,"category":{"id":1,"name":"Clinica medica","enabled":true},
             "weeklySchedules":[
             {"dayOfWeek":"MONDAY","startTime":"12:00:00","endTime":"15:00:00","slotDurationMinutes":30,"enabled":true}]}
            """;
    private static final String REGISTER_BODY = """
            {"login":"e2e.turnos","password":"secreta123","firstName":"Prueba","lastName":"E2E",
             "email":"e2e.turnos@example.com","langKey":"es"}
            """;

    @DynamicPropertySource
    static void simulatedCatalog(DynamicPropertyRegistry registry) {
        CATALOG.respondToAnyPath(FakeHttpServer.Response.of(200, PROFESSIONAL));
        registry.add("TURNOS_CATALOG_BASE_URL", CATALOG::baseUrl);
    }

    @AfterAll
    static void stopCatalog() {
        CATALOG.close();
    }

    @Autowired
    private RestTestClient restTestClient;
    @Autowired
    private TechnicalSessionRepository technicalSessionRepository;

    @Test
    void aRegisteredUserGetsFreeSlotsWithTheRealOccupancies(CapturedOutput output) {
        await().atMost(Duration.ofSeconds(30)).until(() -> technicalSessionRepository.get().isEnabled());
        restTestClient.post().uri("/api/register").contentType(MediaType.APPLICATION_JSON).body(REGISTER_BODY)
                .exchange().expectStatus().isCreated();
        String login = restTestClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                .body("{\"username\":\"e2e.turnos\",\"password\":\"secreta123\"}")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        String userToken = JsonPath.read(login, "$.id_token");
        LocalDate monday = LocalDate.now(ZoneId.of("America/Argentina/Mendoza")).with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        String body = restTestClient.get().uri("/api/free-slots?professionalId=201&date={date}", monday)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        List<String> startTimes = JsonPath.read(body, "$.slots[*].startTime");
        assertThat(startTimes).isSubsetOf("12:00", "12:30", "13:00", "13:30", "14:00", "14:30");
        assertThat(JsonPath.<Boolean>read(body, "$.stale")).isFalse();
        System.out.println("E2E free slots for professional 201 on " + monday + ": " + startTimes);
        assertThat(output.getAll()).doesNotContain(userToken);
    }
}
