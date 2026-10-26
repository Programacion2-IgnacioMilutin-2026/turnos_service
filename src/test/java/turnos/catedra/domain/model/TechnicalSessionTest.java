package turnos.catedra.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalSessionTest {

    private static final Duration INTERVAL = Duration.ofSeconds(60);
    private static final Instant LAST_ATTEMPT = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    void aConfigurationErrorNeverAllowsAnotherLogin() {
        TechnicalSession session = TechnicalSession.configurationError(LAST_ATTEMPT);

        assertThat(session.canAttemptLogin(LAST_ATTEMPT.plus(Duration.ofDays(365)), INTERVAL)).isFalse();
    }

    @Test
    void whileConnectingTheInitialLoginIsAlreadyInProgress() {
        assertThat(TechnicalSession.connecting().canAttemptLogin(LAST_ATTEMPT, INTERVAL)).isFalse();
    }

    @Test
    void aDegradedSessionAllowsALoginOnlyAfterTheInterval() {
        TechnicalSession session = TechnicalSession.degraded(LAST_ATTEMPT);

        assertThat(session.canAttemptLogin(LAST_ATTEMPT.plusSeconds(3), INTERVAL)).isFalse();
        assertThat(session.canAttemptLogin(LAST_ATTEMPT.plusSeconds(59), INTERVAL)).isFalse();
        assertThat(session.canAttemptLogin(LAST_ATTEMPT.plusSeconds(60), INTERVAL)).isTrue();
    }

    @Test
    void anEnabledSessionAlsoRespectsTheInterval() {
        TechnicalSession session = TechnicalSession.enabled("jwt", integration(), LAST_ATTEMPT);

        assertThat(session.canAttemptLogin(LAST_ATTEMPT.plusSeconds(10), INTERVAL)).isFalse();
        assertThat(session.canAttemptLogin(LAST_ATTEMPT.plusSeconds(61), INTERVAL)).isTrue();
    }

    @Test
    void onlyAnEnabledSessionIsEnabled() {
        assertThat(TechnicalSession.enabled("jwt", integration(), LAST_ATTEMPT).isEnabled()).isTrue();
        assertThat(TechnicalSession.connecting().isEnabled()).isFalse();
        assertThat(TechnicalSession.degraded(LAST_ATTEMPT).isEnabled()).isFalse();
        assertThat(TechnicalSession.configurationError(LAST_ATTEMPT).isEnabled()).isFalse();
    }

    @Test
    void theTokenNeverAppearsInTheTextOfTheSessionOrTheResult() {
        String token = "eyJhbGciOiJIUzUxMiJ9.secreto.firma";

        assertThat(TechnicalSession.enabled(token, integration(), LAST_ATTEMPT).toString()).doesNotContain(token);
        assertThat(new TechnicalLoginResult.Success(token, integration()).toString()).doesNotContain(token);
    }

    private TechnicalIntegration integration() {
        return new TechnicalIntegration("proyecto-juan-perez", "kafka:9092", "alumnos-proyecto-juan-perez",
                "alumnos.turnos.acciones.proyecto-juan-perez", "catedra.turnos.telefono.proyecto-juan-perez",
                ProvisioningStatus.PROVISIONED);
    }
}
