package turnos.integration.infrastructure.client;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.integration.FakeCatedraServer;
import turnos.integration.domain.model.ProvisioningStatus;
import turnos.integration.domain.model.TechnicalIntegration;
import turnos.integration.domain.model.TechnicalLoginResult;
import turnos.integration.infrastructure.client.dto.IntegrationResponse;
import turnos.integration.infrastructure.client.mapper.TechnicalIntegrationMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CatedraLoginClientAdapterTest {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);

    private final FakeCatedraServer catedra = FakeCatedraServer.start();

    @AfterEach
    void stopCatedra() {
        catedra.close();
    }

    @Test
    void sendsTheConfiguredCredentialsWithRememberMe() {
        adapter(catedra.baseUrl(), "test-login", "test-password").login();

        assertThat(catedra.receivedRequests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/api/authenticate");
            assertThat(request.contentType()).startsWith("application/json");
            assertThat(JsonPath.<String>read(request.body(), "$.username")).isEqualTo("test-login");
            assertThat(JsonPath.<String>read(request.body(), "$.password")).isEqualTo("test-password");
            assertThat(JsonPath.<Boolean>read(request.body(), "$.rememberMe")).isTrue();
        });
    }

    @Test
    void aProvisionedResponseIsASuccessWithTheFiveFieldsTurnosUses() {
        TechnicalLoginResult result = adapter(catedra.baseUrl(), "test-login", "test-password").login();

        assertThat(result).isEqualTo(new TechnicalLoginResult.Success(FakeCatedraServer.TOKEN, new TechnicalIntegration(
                "proyecto-juan-perez", "kafka.catedra.internal:9092", "alumnos-proyecto-juan-perez",
                "alumnos.turnos.acciones.proyecto-juan-perez", "catedra.turnos.telefono.proyecto-juan-perez",
                ProvisioningStatus.PROVISIONED)));
    }

    @Test
    void anotherProvisioningStatusIsStillASuccessThatTheUseCaseWillNotEnable() {
        catedra.respond(200, FakeCatedraServer.provisionedLoginResponse("PENDING"));

        TechnicalLoginResult result = adapter(catedra.baseUrl(), "test-login", "test-password").login();

        assertThat(result).isInstanceOfSatisfying(TechnicalLoginResult.Success.class,
                success -> assertThat(success.integration().provisioningStatus()).isEqualTo(ProvisioningStatus.PENDING));
    }

    static Stream<Arguments> rejections() {
        return Stream.of(
                Arguments.of(401, null),
                Arguments.of(403, "STUDENT_INTEGRATION_NOT_FOUND"),
                Arguments.of(404, "STUDENT_INTEGRATION_NOT_FOUND"),
                Arguments.of(400, "VALIDATION_ERROR"));
    }

    @ParameterizedTest(name = "HTTP {0} {1}")
    @MethodSource("rejections")
    void aRejectionIsAConfigurationError(int status, String code) {
        catedra.respond(status, FakeCatedraServer.problem(status, code));

        TechnicalLoginResult result = adapter(catedra.baseUrl(), "test-login", "test-password").login();

        assertThat(result).isInstanceOfSatisfying(TechnicalLoginResult.ConfigurationError.class, error -> {
            assertThat(error.reason()).contains("HTTP " + status);
            if (code != null) {
                assertThat(error.reason()).contains(code);
            }
        });
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {500, 502, 503})
    void aServerErrorMeansTheCatedraIsUnavailable(int status) {
        catedra.respond(status, FakeCatedraServer.problem(status, null));

        assertThat(adapter(catedra.baseUrl(), "test-login", "test-password").login())
                .isInstanceOf(TechnicalLoginResult.Unavailable.class);
    }

    @Test
    void aResponseSlowerThanTheReadTimeoutMeansTheCatedraIsUnavailable() {
        catedra.respondAfter(READ_TIMEOUT.multipliedBy(3), 200, FakeCatedraServer.provisionedLoginResponse("PROVISIONED"));

        assertThat(adapter(catedra.baseUrl(), "test-login", "test-password").login())
                .isInstanceOf(TechnicalLoginResult.Unavailable.class);
    }

    @Test
    void aRefusedConnectionMeansTheCatedraIsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThat(adapter("http://localhost:" + closedPort, "test-login", "test-password").login())
                .isInstanceOf(TechnicalLoginResult.Unavailable.class);
    }

    static Stream<Arguments> missingProperties() {
        return Stream.of(
                Arguments.of("turnos.catedra.base-url", null, "test-login", "test-password"),
                Arguments.of("turnos.catedra.username", "BASE_URL", " ", "test-password"),
                Arguments.of("turnos.catedra.password", "BASE_URL", "test-login", ""));
    }

    @ParameterizedTest(name = "sin {0}")
    @MethodSource("missingProperties")
    void aMissingPropertyIsWarnedAndNoRequestIsSent(String property, String baseUrl, String username, String password,
                                                     CapturedOutput output) {
        String url = "BASE_URL".equals(baseUrl) ? catedra.baseUrl() : baseUrl;

        TechnicalLoginResult result = adapter(url, username, password).login();

        assertThat(result).isInstanceOf(TechnicalLoginResult.ConfigurationError.class);
        assertThat(output.getAll()).contains("WARN").contains("missing configuration properties").contains(property);
        assertThat(catedra.receivedRequests()).isEmpty();
    }

    @Test
    void neitherTheTokenNorThePasswordReachTheOutput(CapturedOutput output) {
        CatedraLoginClientAdapter adapter = adapter(catedra.baseUrl(), "test-login", "test-password");
        TechnicalLoginResult success = adapter.login();
        catedra.respond(401, FakeCatedraServer.problem(401, null));
        TechnicalLoginResult rejection = adapter.login();

        assertThat(success + " " + rejection).doesNotContain(FakeCatedraServer.TOKEN).doesNotContain("test-password");
        assertThat(output.getAll()).doesNotContain(FakeCatedraServer.TOKEN).doesNotContain("test-password");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\"http://localhost:8080\"", "'http://localhost:8080'", "localhost:8080", "ftp://localhost", "http://"})
    void anInvalidBaseUrlIsWarnedAndNoLoginIsAttempted(String baseUrl, CapturedOutput output) {
        TechnicalLoginResult result = adapter(baseUrl, "test-login", "test-password").login();

        assertThat(result).isInstanceOf(TechnicalLoginResult.ConfigurationError.class);
        assertThat(output.getAll()).contains("WARN").contains("turnos.catedra.base-url is not a valid http or https URL");
        if (baseUrl.startsWith("\"") || baseUrl.startsWith("'")) {
            assertThat(output.getAll()).contains("remove the quotes from the .env value");
        }
    }

    @Test
    void anUnexpectedErrorMeansTheCatedraIsUnavailable() {
        TechnicalIntegrationMapper failingMapper = new TechnicalIntegrationMapper() {
            @Override
            public TechnicalIntegration toDomain(IntegrationResponse response) {
                throw new IllegalStateException("unexpected");
            }
        };

        TechnicalLoginResult result = adapter(catedra.baseUrl(), "test-login", "test-password", failingMapper).login();

        assertThat(result).isEqualTo(new TechnicalLoginResult.Unavailable(
                "Unexpected error during the technical login: IllegalStateException"));
    }

    private CatedraLoginClientAdapter adapter(String baseUrl, String username, String password) {
        return adapter(baseUrl, username, password, new TechnicalIntegrationMapper());
    }

    private CatedraLoginClientAdapter adapter(String baseUrl, String username, String password, TechnicalIntegrationMapper mapper) {
        CatedraProperties properties = new CatedraProperties(baseUrl, username, password,
                Duration.ofSeconds(2), READ_TIMEOUT, new CatedraProperties.Login(5, Duration.ofSeconds(1), 2,
                Duration.ofSeconds(30), Duration.ofSeconds(60)));
        return new CatedraLoginClientAdapter(properties, mapper);
    }
}
