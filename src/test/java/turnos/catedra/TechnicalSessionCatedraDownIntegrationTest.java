package turnos.catedra;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import turnos.TestcontainersConfiguration;
import turnos.catedra.domain.model.IntegrationState;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa con la cátedra caída (503): queda degradada y T01 y T02 siguen funcionando
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class TechnicalSessionCatedraDownIntegrationTest {

    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();

    static {
        CATEDRA.respond(503, FakeCatedraServer.problem(503, null));
    }

    @DynamicPropertySource
    static void catedraProperties(DynamicPropertyRegistry registry) {
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "test-login");
        registry.add("turnos.catedra.password", () -> "test-password");
        // Mismas reglas, con esperas cortas para que el test no tarde 15 segundos
        registry.add("turnos.catedra.login.initial-delay", () -> "10ms");
        registry.add("turnos.catedra.login.max-delay", () -> "40ms");
    }

    @AfterAll
    static void stopCatedra() {
        CATEDRA.close();
    }

    @Autowired
    private TechnicalSessionRepository technicalSessionRepository;

    @Autowired
    private RestTestClient restTestClient;

    @Test
    void theServiceDegradesAfterTheRetriesAndKeepsServingRegisterAndLogin(CapturedOutput output) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> technicalSessionRepository.get().state() == IntegrationState.DEGRADED);

        assertThat(CATEDRA.receivedRequests()).hasSize(5);
        assertThat(output.getAll())
                .contains("Technical login attempt 1/5 failed: Central API answered HTTP 503")
                .contains("Technical login not completed after 5 attempts: integration degraded");

        restTestClient.post().uri("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"login":"maria.gomez","password":"secreta123","firstName":"María","lastName":"Gómez",
                         "email":"maria.gomez@example.com","langKey":"es"}
                        """)
                .exchange()
                .expectStatus().isCreated();
        restTestClient.post().uri("/api/authenticate")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"username\":\"maria.gomez\",\"password\":\"secreta123\"}")
                .exchange()
                .expectStatus().isOk();
    }
}
