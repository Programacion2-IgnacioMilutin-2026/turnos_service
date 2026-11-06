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
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import turnos.FakeHttpServer;
import turnos.TestcontainersConfiguration;
import turnos.TurnosServiceApplication;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Contra la cátedra real, con la cuenta técnica del .env local: solo con ./mvnw test -Pe2e.
// Sin credenciales se saltea. El catálogo es simulado mientras no exista el real, con un profesional habilitado de la
// cátedra; la base es de Testcontainers
@Tag("e2e")
@EnabledIf(value = "e2e.E2ECredentials#available", disabledReason = "No catedra credentials in the local .env: E2E tests skipped")
@SpringBootTest(classes = TurnosServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class FreeSlotsE2ETest {

    // Responde con el profesional que se elige del snapshot de la cátedra (E2EProfessional)
    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    private static final String REGISTER_BODY = """
            {"login":"e2e.turnos","password":"secreta123","firstName":"Prueba","lastName":"E2E",
             "email":"e2e.turnos@example.com","langKey":"es"}
            """;

    @DynamicPropertySource
    static void simulatedCatalog(DynamicPropertyRegistry registry) {
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
    @Autowired
    private Environment environment;

    @Test
    void aRegisteredUserGetsFreeSlotsWithTheRealOccupancies(CapturedOutput output) {
        await().atMost(Duration.ofSeconds(30)).until(() -> technicalSessionRepository.get().isEnabled());
        restTestClient.post().uri("/api/register").contentType(MediaType.APPLICATION_JSON).body(REGISTER_BODY)
                .exchange().expectStatus().isCreated();
        String login = restTestClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                .body("{\"username\":\"e2e.turnos\",\"password\":\"secreta123\"}")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        String userToken = JsonPath.read(login, "$.id_token");
        List<E2EProfessional> professionals = E2EProfessional.enabledInTheCatedra(
                environment.getProperty("turnos.catedra.base-url"), technicalSessionRepository.get().token());
        E2EProfessional.Choice choice = E2EProfessional.choose(professionals, CATALOG, 0,
                (professionalId, date) -> JsonPath.read(freeSlots(userToken, professionalId, date), "$.slots[*].startTime"));

        String body = freeSlots(userToken, choice.professional().id(), choice.date());

        List<String> startTimes = JsonPath.read(body, "$.slots[*].startTime");
        assertThat(startTimes).isSubsetOf(choice.professional().slotsOn(choice.date()));
        assertThat(JsonPath.<Boolean>read(body, "$.stale")).isFalse();
        System.out.println("E2E free slots for professional " + choice.professional().id() + " on " + choice.date() + ": " + startTimes);
        assertThat(output.getAll()).doesNotContain(userToken);
    }

    private String freeSlots(String userToken, long professionalId, LocalDate date) {
        return restTestClient.get().uri("/api/free-slots?professionalId={id}&date={date}", professionalId, date)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }
}
