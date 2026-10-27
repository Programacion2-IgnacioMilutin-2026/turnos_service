package turnos.catedra.application.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.MutableClock;
import turnos.catedra.application.usecases.RenewTechnicalSessionUseCaseImpl;
import turnos.catedra.application.usecases.ScriptedLoginClient;
import turnos.catedra.domain.model.CatedraOperationPolicy;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalLoginPolicy;
import turnos.catedra.domain.model.TechnicalLoginResult;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.infrastructure.persistence.adapter.InMemoryTechnicalSessionRepositoryAdapter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CatedraOperationRunnerTest {

    private static final Instant START = Instant.parse("2026-10-27T12:00:00Z");
    private static final String OPERATION = "test request for professional 201";
    private static final CatedraResponse<String> ANSWERED = new CatedraResponse.Answered<>("content");
    private static final CatedraResponse<String> UNAVAILABLE = new CatedraResponse.Unavailable<>("Catedra answered HTTP 503");

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();
    private final ScriptedLoginClient loginClient = new ScriptedLoginClient();
    private final RenewTechnicalSessionUseCaseImpl renew = new RenewTechnicalSessionUseCaseImpl(loginClient, repository,
            new TechnicalLoginPolicy(1, Duration.ofMillis(1), 2, Duration.ofMillis(1), Duration.ofSeconds(60)), clock);
    private final CatedraOperationRunner runner =
            new CatedraOperationRunner(repository, renew, new CatedraOperationPolicy(3, Duration.ofMillis(1), 2));
    private final ScriptedCall call = new ScriptedCall();

    @Test
    void anEnabledSessionUsesItsCurrentTokenWithoutLoggingIn() {
        repository.save(enabled("jwt-a"));
        call.then(ANSWERED);

        assertThat(runner.run(OPERATION, call)).isEqualTo(ANSWERED);
        assertThat(call.tokens()).containsExactly("jwt-a");
        assertThat(loginClient.calls()).isZero();
    }

    @Test
    void aConfigurationErrorAnswersWithoutCallingTheCatedra() {
        repository.save(TechnicalSession.configurationError(START));

        assertThat(runner.run(OPERATION, call)).isInstanceOf(CatedraResponse.ConfigurationError.class);
        assertThat(call.tokens()).isEmpty();
        assertThat(loginClient.calls()).isZero();
    }

    @Test
    void aDegradedSessionLogsInFirstAndUsesTheNewToken() {
        repository.save(TechnicalSession.degraded(START.minusSeconds(120)));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        call.then(ANSWERED);

        assertThat(runner.run(OPERATION, call)).isEqualTo(ANSWERED);
        assertThat(loginClient.calls()).isEqualTo(1);
        assertThat(call.tokens()).containsExactly("jwt-b");
    }

    @Test
    void withoutAPossibleSessionTheCatedraIsUnavailableAndIsNotCalled() {
        repository.save(TechnicalSession.degraded(START.minusSeconds(120)));
        loginClient.then(new TechnicalLoginResult.Unavailable("Catedra answered HTTP 503"));

        assertThat(runner.run(OPERATION, call)).isInstanceOf(CatedraResponse.Unavailable.class);
        assertThat(call.tokens()).isEmpty();
    }

    @Test
    void aSessionStillConnectingIsUnavailableWithoutAnotherLogin() {
        repository.save(TechnicalSession.connecting());

        assertThat(runner.run(OPERATION, call)).isInstanceOf(CatedraResponse.Unavailable.class);
        assertThat(loginClient.calls()).isZero();
        assertThat(call.tokens()).isEmpty();
    }

    @Test
    void aRejectedTokenRenewsTheSessionAndRepeatsOnceWithTheNewToken() {
        repository.save(enabled("jwt-a"));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        call.then(new CatedraResponse.TokenRejected<>()).then(ANSWERED);

        assertThat(runner.run(OPERATION, call)).isEqualTo(ANSWERED);
        assertThat(loginClient.calls()).isEqualTo(1);
        assertThat(call.tokens()).containsExactly("jwt-a", "jwt-b");
    }

    @Test
    void aSecondRejectionIsNotRepeatedAgainAndIsUnavailable(CapturedOutput output) {
        repository.save(enabled("jwt-a"));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        call.then(new CatedraResponse.TokenRejected<>());

        assertThat(runner.run(OPERATION, call)).isInstanceOf(CatedraResponse.Unavailable.class);
        assertThat(loginClient.calls()).isEqualTo(1);
        assertThat(call.tokens()).containsExactly("jwt-a", "jwt-b");
        assertThat(output.getAll()).contains("Catedra rejected the renewed technical token for the " + OPERATION);
    }

    @Test
    void aRejectedTokenWithoutARenewedSessionIsUnavailable() {
        repository.save(enabled("jwt-a"));
        loginClient.then(new TechnicalLoginResult.Unavailable("Catedra is not reachable"));
        call.then(new CatedraResponse.TokenRejected<>());

        assertThat(runner.run(OPERATION, call)).isInstanceOf(CatedraResponse.Unavailable.class);
        assertThat(call.tokens()).containsExactly("jwt-a");
    }

    @Test
    void anUnavailableCatedraIsRetriedUpToTheMaximum() {
        repository.save(enabled("jwt-a"));
        call.then(UNAVAILABLE);

        assertThat(runner.run(OPERATION, call)).isEqualTo(UNAVAILABLE);
        assertThat(call.tokens()).containsExactly("jwt-a", "jwt-a", "jwt-a");
    }

    @Test
    void aLaterSuccessfulAttemptEndsTheRetries() {
        repository.save(enabled("jwt-a"));
        call.then(UNAVAILABLE).then(ANSWERED);

        assertThat(runner.run(OPERATION, call)).isEqualTo(ANSWERED);
        assertThat(call.tokens()).hasSize(2);
    }

    @Test
    void aCallThatMayHaveBeenProcessedIsNeverRepeated() {
        repository.save(enabled("jwt-a"));
        CatedraResponse<String> uncertain = new CatedraResponse.Uncertain<>("Catedra answered HTTP 500");
        call.then(uncertain).then(ANSWERED);

        assertThat(runner.run(OPERATION, call)).isEqualTo(uncertain);
        assertThat(call.tokens()).hasSize(1);
    }

    @Test
    void anUnexpectedAnswerIsAFailureWithoutRetrying() {
        repository.save(enabled("jwt-a"));
        call.then(new CatedraResponse.Failed<>("Catedra answered HTTP 502"));

        assertThat(runner.run(OPERATION, call)).isInstanceOf(CatedraResponse.Failed.class);
        assertThat(call.tokens()).hasSize(1);
    }

    @Test
    void aMissingStudentIntegrationIsLoggedAsAnErrorWithoutRetrying(CapturedOutput output) {
        repository.save(enabled("jwt-a"));
        call.then(new CatedraResponse.ConfigurationError<>("Catedra answered STUDENT_INTEGRATION_NOT_FOUND"));

        assertThat(runner.run(OPERATION, call)).isInstanceOf(CatedraResponse.ConfigurationError.class);
        assertThat(call.tokens()).hasSize(1);
        assertThat(output.getAll()).contains("ERROR").contains("STUDENT_INTEGRATION_NOT_FOUND");
    }

    @Test
    void anUnexpectedErrorIsAFailureWithoutRetrying() {
        repository.save(enabled("jwt-a"));
        call.then(() -> {
            throw new IllegalStateException("broken client");
        });

        assertThat(runner.run(OPERATION, call)).isInstanceOf(CatedraResponse.Failed.class);
        assertThat(call.tokens()).hasSize(1);
    }

    @Test
    void eachFailedAttemptIsLoggedWithTheOperationAndWithoutTheToken(CapturedOutput output) {
        repository.save(enabled("jwt-secreto-a"));
        call.then(UNAVAILABLE);

        runner.run(OPERATION, call);

        assertThat(output.getAll())
                .contains("Catedra " + OPERATION + " attempt 1/3 failed: Catedra answered HTTP 503")
                .contains("attempt 3/3 failed")
                .doesNotContain("jwt-secreto-a");
    }

    // El último login fue hace dos minutos: ya pasó el intervalo mínimo para pedir otro
    private static TechnicalSession enabled(String token) {
        return TechnicalSession.enabled(token, ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED),
                START.minusSeconds(120));
    }

    // Responde en orden lo indicado (la última respuesta se repite) y guarda el token de cada llamada
    private static final class ScriptedCall implements Function<String, CatedraResponse<String>> {

        private final List<Supplier<CatedraResponse<String>>> script = new ArrayList<>();
        private final List<String> tokens = new ArrayList<>();

        ScriptedCall then(CatedraResponse<String> response) {
            return then(() -> response);
        }

        ScriptedCall then(Supplier<CatedraResponse<String>> response) {
            script.add(response);
            return this;
        }

        List<String> tokens() {
            return tokens;
        }

        @Override
        public CatedraResponse<String> apply(String token) {
            tokens.add(token);
            return script.get(Math.min(tokens.size(), script.size()) - 1).get();
        }
    }
}
