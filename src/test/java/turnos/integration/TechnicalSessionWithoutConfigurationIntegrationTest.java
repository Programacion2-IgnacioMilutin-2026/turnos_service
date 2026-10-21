package turnos.integration;

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
import turnos.integration.domain.model.IntegrationState;
import turnos.integration.domain.ports.out.TechnicalSessionRepository;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// La aplicación completa sin usuario ni contraseña de la cátedra: arranca, avisa y nunca llama a la cátedra
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class TechnicalSessionWithoutConfigurationIntegrationTest {

    private static final FakeCatedraServer CATEDRA = FakeCatedraServer.start();

    @DynamicPropertySource
    static void catedraProperties(DynamicPropertyRegistry registry) {
        registry.add("turnos.catedra.base-url", CATEDRA::baseUrl);
        registry.add("turnos.catedra.username", () -> "");
        registry.add("turnos.catedra.password", () -> "");
    }

    @AfterAll
    static void stopCatedra() {
        CATEDRA.close();
    }

    @Autowired
    private TechnicalSessionRepository technicalSessionRepository;

    @Test
    void theServiceStartsWarnsAndNeverCallsTheCatedra(CapturedOutput output) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> technicalSessionRepository.get().state() == IntegrationState.CONFIGURATION_ERROR);

        assertThat(output.getAll())
                .contains("WARN")
                .contains("Technical login disabled: missing configuration properties [turnos.catedra.username, turnos.catedra.password]");
        assertThat(CATEDRA.receivedRequests()).isEmpty();
    }
}
