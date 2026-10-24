package turnos.catedra;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import turnos.TestcontainersConfiguration;
import turnos.catedra.application.service.TechnicalSessionService;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalSession;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa contra una cátedra simulada que responde PROVISIONED
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class TechnicalSessionIntegrationTest {

    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();

    @DynamicPropertySource
    static void catedraProperties(DynamicPropertyRegistry registry) {
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "test-login");
        registry.add("turnos.catedra.password", () -> "test-password");
    }

    @AfterAll
    static void stopCatedra() {
        CATEDRA.close();
    }

    @Autowired
    private TechnicalSessionService technicalSessionService;

    @Test
    void atStartupTheSessionBecomesEnabledWithTheCatedraIntegration(CapturedOutput output) {
        TechnicalSession session = await().atMost(Duration.ofSeconds(10))
                .until(technicalSessionService::getTechnicalSession, Optional::isPresent)
                .orElseThrow();

        assertThat(session.token()).isEqualTo(FakeCatedraServer.TOKEN);
        assertThat(session.integration().groupId()).isEqualTo("proyecto-juan-perez");
        assertThat(session.integration().kafkaAppointmentPhoneTopic()).isEqualTo("catedra.turnos.telefono.proyecto-juan-perez");
        assertThat(session.integration().provisioningStatus()).isEqualTo(ProvisioningStatus.PROVISIONED);
        assertThat(CATEDRA.receivedRequests()).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/api/authenticate");
            assertThat(JsonPath.<String>read(request.body(), "$.username")).isEqualTo("test-login");
            assertThat(JsonPath.<Boolean>read(request.body(), "$.rememberMe")).isTrue();
        });
        assertThat(output.getAll())
                .contains("Technical login succeeded: groupId=proyecto-juan-perez, provisioningStatus=PROVISIONED")
                .doesNotContain(FakeCatedraServer.TOKEN)
                .doesNotContain("test-password");
    }
}
