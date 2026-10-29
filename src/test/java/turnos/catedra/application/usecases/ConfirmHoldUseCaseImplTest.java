package turnos.catedra.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.MutableClock;
import turnos.catedra.application.support.CatedraOperationRunner;
import turnos.catedra.domain.model.CatedraOperationPolicy;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.HoldConfirmation;
import turnos.catedra.domain.model.HoldConfirmationResult;
import turnos.catedra.domain.model.HoldResult;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalLoginPolicy;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.out.CatedraHoldClient;
import turnos.catedra.infrastructure.persistence.adapter.InMemoryTechnicalSessionRepositoryAdapter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ConfirmHoldUseCaseImplTest {

    private static final Instant START = Instant.parse("2026-10-28T12:00:00Z");
    private static final HoldConfirmationResult CONFIRMED = new HoldConfirmationResult.Confirmed(
            new HoldConfirmation("process-1", Instant.parse("2026-10-28T12:10:00Z")));

    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();
    private final ScriptedLoginClient loginClient = new ScriptedLoginClient();
    private final ScriptedConfirmationClient confirmationClient = new ScriptedConfirmationClient();
    private final ConfirmHoldUseCaseImpl useCase = new ConfirmHoldUseCaseImpl(confirmationClient, new CatedraOperationRunner(
            repository,
            new RenewTechnicalSessionUseCaseImpl(loginClient, repository,
                    new TechnicalLoginPolicy(1, Duration.ofMillis(1), 2, Duration.ofMillis(1), Duration.ofSeconds(60)),
                    new MutableClock(START)),
            new CatedraOperationPolicy(3, Duration.ofMillis(1), 2)));

    @Test
    void theHoldAndThePatientReachTheClientWithTheCurrentToken() {
        repository.save(enabled("jwt-a"));
        confirmationClient.then(new CatedraResponse.Answered<>(CONFIRMED));

        assertThat(useCase.confirmHold("hold-1", "42", "María", "Gómez")).isEqualTo(CONFIRMED);
        assertThat(confirmationClient.calls()).containsExactly("hold-1 42 María Gómez jwt-a");
    }

    @Test
    void eachDefinitiveAnswerIsReturnedAsIs() {
        repository.save(enabled("jwt-a"));
        for (HoldConfirmationResult answer : List.of(new HoldConfirmationResult.AlreadyConfirmed(),
                new HoldConfirmationResult.HoldNotFound(), new HoldConfirmationResult.HoldExpired(),
                new HoldConfirmationResult.OwnerMismatch(), new HoldConfirmationResult.PatientMismatch(),
                new HoldConfirmationResult.ProfessionalNotFound(), new HoldConfirmationResult.InvalidSlot(),
                new HoldConfirmationResult.SlotNotAvailable())) {
            confirmationClient.reset().then(new CatedraResponse.Answered<>(answer));

            assertThat(useCase.confirmHold("hold-1", "42", "María", "Gómez")).isEqualTo(answer);
            assertThat(confirmationClient.calls()).hasSize(1);
        }
    }

    @Test
    void aCatedraThatDoesNotAnswerIsRetriedUpToTheMaximumAndEachAttemptIsLogged(CapturedOutput output) {
        repository.save(enabled("jwt-a"));
        confirmationClient.then(new CatedraResponse.Unavailable<>("Catedra answered HTTP 500"));

        assertThat(useCase.confirmHold("hold-1", "42", "María", "Gómez"))
                .isInstanceOf(HoldConfirmationResult.Unavailable.class);
        assertThat(confirmationClient.calls()).hasSize(3);
        assertThat(output.getAll()).contains("confirmation of hold-1 attempt 1/3 failed: Catedra answered HTTP 500")
                .contains("attempt 3/3").doesNotContain("jwt-a");
    }

    @Test
    void aRetryOfAConfirmationThatWasProcessedGetsItsAnswer() {
        repository.save(enabled("jwt-a"));
        confirmationClient.then(new CatedraResponse.Unavailable<>("Catedra did not answer in time"))
                .then(new CatedraResponse.Answered<>(new HoldConfirmationResult.AlreadyConfirmed()));

        assertThat(useCase.confirmHold("hold-1", "42", "María", "Gómez"))
                .isEqualTo(new HoldConfirmationResult.AlreadyConfirmed());
        assertThat(confirmationClient.calls()).hasSize(2);
    }

    @Test
    void aLaterAttemptCanStillConfirmTheHold() {
        repository.save(enabled("jwt-a"));
        confirmationClient.then(new CatedraResponse.Unavailable<>("Catedra is not reachable"))
                .then(new CatedraResponse.Answered<>(CONFIRMED));

        assertThat(useCase.confirmHold("hold-1", "42", "María", "Gómez")).isEqualTo(CONFIRMED);
        assertThat(confirmationClient.calls()).hasSize(2);
    }

    @Test
    void aRejectedTokenRenewsTheSessionAndRepeatsOnce() {
        repository.save(enabled("jwt-a"));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        confirmationClient.then(new CatedraResponse.TokenRejected<>()).then(new CatedraResponse.Answered<>(CONFIRMED));

        assertThat(useCase.confirmHold("hold-1", "42", "María", "Gómez")).isEqualTo(CONFIRMED);
        assertThat(confirmationClient.calls()).containsExactly("hold-1 42 María Gómez jwt-a", "hold-1 42 María Gómez jwt-b");
    }

    @Test
    void aRenewedTokenRejectedAgainIsNotRepeatedAnyMore() {
        repository.save(enabled("jwt-a"));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        confirmationClient.then(new CatedraResponse.TokenRejected<>());

        assertThat(useCase.confirmHold("hold-1", "42", "María", "Gómez"))
                .isInstanceOf(HoldConfirmationResult.Unavailable.class);
        assertThat(confirmationClient.calls()).hasSize(2);
    }

    @Test
    void integrationProblemsKeepTheirMeaning() {
        repository.save(enabled("jwt-a"));
        confirmationClient.then(new CatedraResponse.ConfigurationError<>("Catedra answered STUDENT_INTEGRATION_NOT_FOUND"));
        assertThat(useCase.confirmHold("hold-1", "42", "María", "Gómez"))
                .isInstanceOf(HoldConfirmationResult.ConfigurationError.class);

        confirmationClient.reset().then(new CatedraResponse.Failed<>("Catedra answered HTTP 502"));
        assertThat(useCase.confirmHold("hold-1", "42", "María", "Gómez")).isInstanceOf(HoldConfirmationResult.Failed.class);
        assertThat(confirmationClient.calls()).hasSize(1);

        repository.save(TechnicalSession.configurationError(START));
        assertThat(useCase.confirmHold("hold-1", "42", "María", "Gómez"))
                .isInstanceOf(HoldConfirmationResult.ConfigurationError.class);
    }

    // El último intento de login fue recién: todavía no pasó el intervalo mínimo para pedir otro
    @Test
    void aDegradedSessionWithoutAPossibleLoginIsUnavailable() {
        repository.save(TechnicalSession.degraded(START));

        assertThat(useCase.confirmHold("hold-1", "42", "María", "Gómez"))
                .isInstanceOf(HoldConfirmationResult.Unavailable.class);
        assertThat(confirmationClient.calls()).isEmpty();
    }

    // El último login fue hace dos minutos: ya pasó el intervalo mínimo para pedir otro
    private static TechnicalSession enabled(String token) {
        return TechnicalSession.enabled(token, ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED),
                START.minusSeconds(120));
    }

    // Responde en orden lo indicado (la última respuesta se repite) y guarda los datos de cada llamada
    private static final class ScriptedConfirmationClient implements CatedraHoldClient {

        private final List<CatedraResponse<HoldConfirmationResult>> script = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();

        ScriptedConfirmationClient then(CatedraResponse<HoldConfirmationResult> response) {
            script.add(response);
            return this;
        }

        ScriptedConfirmationClient reset() {
            script.clear();
            calls.clear();
            return this;
        }

        List<String> calls() {
            return calls;
        }

        @Override
        public CatedraResponse<HoldConfirmationResult> confirmHold(String holdId, String externalPatientId,
                                                                   String patientFirstName, String patientLastName,
                                                                   String technicalToken) {
            calls.add(holdId + " " + externalPatientId + " " + patientFirstName + " " + patientLastName + " " + technicalToken);
            return script.get(Math.min(calls.size(), script.size()) - 1);
        }

        @Override
        public CatedraResponse<HoldResult> createHold(Long professionalId, LocalDate date, LocalTime startTime,
                                                      String externalPatientId, String technicalToken) {
            throw new UnsupportedOperationException("Confirming a hold never creates one");
        }
    }
}
