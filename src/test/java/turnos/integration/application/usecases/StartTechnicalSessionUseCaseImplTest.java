package turnos.integration.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.integration.domain.model.IntegrationState;
import turnos.integration.domain.model.ProvisioningStatus;
import turnos.integration.domain.model.TechnicalLoginPolicy;
import turnos.integration.domain.model.TechnicalLoginResult;
import turnos.integration.domain.model.TechnicalSession;
import turnos.integration.infrastructure.persistence.adapter.InMemoryTechnicalSessionRepositoryAdapter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class StartTechnicalSessionUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final String TOKEN = "jwt-tecnico-de-test";

    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();
    private final ScriptedLoginClient client = new ScriptedLoginClient();
    // Mismas reglas que la configuración real, con esperas de milisegundos para que el test sea rápido
    private final TechnicalLoginPolicy policy = new TechnicalLoginPolicy(
            5, Duration.ofMillis(1), 2, Duration.ofMillis(10), Duration.ofSeconds(60));
    private final StartTechnicalSessionUseCaseImpl useCase =
            new StartTechnicalSessionUseCaseImpl(client, repository, policy, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aProvisionedLoginEnablesTheSessionOnTheFirstAttempt() {
        client.then(ScriptedLoginClient.success(TOKEN, ProvisioningStatus.PROVISIONED));

        TechnicalSession session = useCase.startTechnicalSession();

        assertThat(client.calls()).isEqualTo(1);
        assertThat(session).isEqualTo(TechnicalSession.enabled(TOKEN,
                ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED), NOW));
        assertThat(repository.get()).isEqualTo(session);
    }

    @ParameterizedTest
    @EnumSource(value = ProvisioningStatus.class, names = "PROVISIONED", mode = EnumSource.Mode.EXCLUDE)
    void anotherProvisioningStatusIsRetriedUpToTheLimitAndDegrades(ProvisioningStatus status) {
        client.then(ScriptedLoginClient.success(TOKEN, status));

        TechnicalSession session = useCase.startTechnicalSession();

        assertThat(client.calls()).isEqualTo(5);
        assertThat(session.state()).isEqualTo(IntegrationState.DEGRADED);
        assertThat(session.token()).isNull();
        assertThat(repository.get()).isEqualTo(session);
    }

    @Test
    void aConfigurationErrorIsNotRetried() {
        client.then(new TechnicalLoginResult.ConfigurationError("HTTP 401"));

        TechnicalSession session = useCase.startTechnicalSession();

        assertThat(client.calls()).isEqualTo(1);
        assertThat(session).isEqualTo(TechnicalSession.configurationError(NOW));
    }

    @Test
    void anUnavailableCatedraIsRetriedUntilItAnswers() {
        client.then(new TechnicalLoginResult.Unavailable("HTTP 503"))
                .then(new TechnicalLoginResult.Unavailable("timeout"))
                .then(ScriptedLoginClient.success(TOKEN, ProvisioningStatus.PROVISIONED));

        TechnicalSession session = useCase.startTechnicalSession();

        assertThat(client.calls()).isEqualTo(3);
        assertThat(session.state()).isEqualTo(IntegrationState.ENABLED);
    }

    @Test
    void anAlwaysUnavailableCatedraDegradesAfterTheLimit() {
        client.then(new TechnicalLoginResult.Unavailable("HTTP 503"));

        TechnicalSession session = useCase.startTechnicalSession();

        assertThat(client.calls()).isEqualTo(5);
        assertThat(session).isEqualTo(TechnicalSession.degraded(NOW));
    }

    @Test
    void anUnexpectedErrorCountsAsAFailedAttemptAndNeverLeavesTheSessionConnecting() {
        client.then(() -> {
            throw new IllegalArgumentException("Bad authority");
        });

        TechnicalSession session = useCase.startTechnicalSession();

        assertThat(client.calls()).isEqualTo(5);
        assertThat(session).isEqualTo(TechnicalSession.degraded(NOW));
        assertThat(repository.get().state()).isEqualTo(IntegrationState.DEGRADED);
    }

    @Test
    void anUnexpectedErrorIsRetriedLikeAnUnavailableCatedra() {
        client.then(() -> {
            throw new IllegalStateException("unexpected");
        }).then(ScriptedLoginClient.success(TOKEN, ProvisioningStatus.PROVISIONED));

        assertThat(useCase.startTechnicalSession().state()).isEqualTo(IntegrationState.ENABLED);
        assertThat(client.calls()).isEqualTo(2);
    }

    @Test
    void theSessionIsConnectingWhileTheAttemptsRun() {
        List<IntegrationState> statesSeenDuringLogin = new ArrayList<>();
        client.then(() -> {
            statesSeenDuringLogin.add(repository.get().state());
            return new TechnicalLoginResult.Unavailable("HTTP 503");
        }).then(() -> {
            statesSeenDuringLogin.add(repository.get().state());
            return ScriptedLoginClient.success(TOKEN, ProvisioningStatus.PROVISIONED);
        });

        useCase.startTechnicalSession();

        assertThat(statesSeenDuringLogin).containsExactly(IntegrationState.CONNECTING, IntegrationState.CONNECTING);
    }

    @Test
    void eachAttemptIsLoggedWithoutTheToken(CapturedOutput output) {
        client.then(new TechnicalLoginResult.Unavailable("HTTP 503"))
                .then(ScriptedLoginClient.success(TOKEN, ProvisioningStatus.PROVISIONED));

        useCase.startTechnicalSession();

        assertThat(output.getAll())
                .contains("Technical login attempt 1/5 failed: HTTP 503")
                .contains("Technical login succeeded: groupId=proyecto-juan-perez, provisioningStatus=PROVISIONED")
                .doesNotContain(TOKEN);
    }

    @Test
    void aConfigurationErrorIsLoggedAsAnError(CapturedOutput output) {
        client.then(new TechnicalLoginResult.ConfigurationError("HTTP 401"));

        useCase.startTechnicalSession();

        assertThat(output.getAll()).contains("ERROR").contains("Technical login configuration error, no retries until restart: HTTP 401");
    }
}
