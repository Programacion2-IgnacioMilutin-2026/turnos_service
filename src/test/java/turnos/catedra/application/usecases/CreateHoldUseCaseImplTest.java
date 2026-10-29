package turnos.catedra.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.MutableClock;
import turnos.catedra.application.support.CatedraOperationRunner;
import turnos.catedra.domain.model.CatedraOperationPolicy;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.Hold;
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
class CreateHoldUseCaseImplTest {

    private static final Instant START = Instant.parse("2026-10-27T12:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final LocalTime NOON = LocalTime.of(12, 0);
    private static final HoldResult CREATED = new HoldResult.Created(
            new Hold("hold-1", "process-1", Instant.parse("2026-10-27T12:10:00Z")));

    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();
    private final ScriptedLoginClient loginClient = new ScriptedLoginClient();
    private final ScriptedHoldClient holdClient = new ScriptedHoldClient();
    private final CreateHoldUseCaseImpl useCase = new CreateHoldUseCaseImpl(holdClient, new CatedraOperationRunner(repository,
            new RenewTechnicalSessionUseCaseImpl(loginClient, repository,
                    new TechnicalLoginPolicy(1, Duration.ofMillis(1), 2, Duration.ofMillis(1), Duration.ofSeconds(60)),
                    new MutableClock(START)),
            new CatedraOperationPolicy(3, Duration.ofMillis(1), 2)));

    @Test
    void theSlotAndThePatientReachTheClientWithTheCurrentToken() {
        repository.save(enabled("jwt-a"));
        holdClient.then(new CatedraResponse.Answered<>(CREATED));

        assertThat(useCase.createHold(201L, DATE, NOON, "42")).isEqualTo(CREATED);
        assertThat(holdClient.calls()).containsExactly("201 2026-11-02 12:00 42 jwt-a");
    }

    @Test
    void eachDefinitiveAnswerIsReturnedAsIs() {
        repository.save(enabled("jwt-a"));
        for (HoldResult answer : List.of(new HoldResult.ProfessionalNotFound(), new HoldResult.ProfessionalDisabled(),
                new HoldResult.InvalidSlot(), new HoldResult.SlotNotAvailable())) {
            holdClient.reset().then(new CatedraResponse.Answered<>(answer));

            assertThat(useCase.createHold(201L, DATE, NOON, "42")).isEqualTo(answer);
            assertThat(holdClient.calls()).hasSize(1);
        }
    }

    @Test
    void whatSurelyDidNotArriveIsRetriedUpToTheMaximum() {
        repository.save(enabled("jwt-a"));
        holdClient.then(new CatedraResponse.Unavailable<>("Catedra answered HTTP 503"));

        assertThat(useCase.createHold(201L, DATE, NOON, "42")).isInstanceOf(HoldResult.Unavailable.class);
        assertThat(holdClient.calls()).hasSize(3);
    }

    @Test
    void aLaterAttemptCanStillCreateTheHold() {
        repository.save(enabled("jwt-a"));
        holdClient.then(new CatedraResponse.Unavailable<>("Catedra is not reachable")).then(new CatedraResponse.Answered<>(CREATED));

        assertThat(useCase.createHold(201L, DATE, NOON, "42")).isEqualTo(CREATED);
        assertThat(holdClient.calls()).hasSize(2);
    }

    @Test
    void whatMayHaveCreatedTheHoldIsNotRetriedAndIsLogged(CapturedOutput output) {
        repository.save(enabled("jwt-a"));
        holdClient.then(new CatedraResponse.Uncertain<>("Catedra answered HTTP 500")).then(new CatedraResponse.Answered<>(CREATED));

        assertThat(useCase.createHold(201L, DATE, NOON, "42")).isInstanceOf(HoldResult.Unavailable.class);
        assertThat(holdClient.calls()).hasSize(1);
        assertThat(output.getAll()).contains("may have created a hold").contains("professional 201 on 2026-11-02 at 12:00")
                .doesNotContain("jwt-a");
    }

    @Test
    void aRejectedTokenRenewsTheSessionAndRepeatsOnce() {
        repository.save(enabled("jwt-a"));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        holdClient.then(new CatedraResponse.TokenRejected<>()).then(new CatedraResponse.Answered<>(CREATED));

        assertThat(useCase.createHold(201L, DATE, NOON, "42")).isEqualTo(CREATED);
        assertThat(holdClient.calls()).containsExactly("201 2026-11-02 12:00 42 jwt-a", "201 2026-11-02 12:00 42 jwt-b");
    }

    @Test
    void integrationProblemsKeepTheirMeaning() {
        repository.save(enabled("jwt-a"));
        holdClient.then(new CatedraResponse.ConfigurationError<>("Catedra answered STUDENT_INTEGRATION_NOT_FOUND"));
        assertThat(useCase.createHold(201L, DATE, NOON, "42")).isInstanceOf(HoldResult.ConfigurationError.class);

        holdClient.reset().then(new CatedraResponse.Failed<>("Catedra answered HTTP 502"));
        assertThat(useCase.createHold(201L, DATE, NOON, "42")).isInstanceOf(HoldResult.Failed.class);
        assertThat(holdClient.calls()).hasSize(1);

        repository.save(TechnicalSession.configurationError(START));
        assertThat(useCase.createHold(201L, DATE, NOON, "42")).isInstanceOf(HoldResult.ConfigurationError.class);
    }

    // El último login fue hace dos minutos: ya pasó el intervalo mínimo para pedir otro
    private static TechnicalSession enabled(String token) {
        return TechnicalSession.enabled(token, ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED),
                START.minusSeconds(120));
    }

    // Responde en orden lo indicado (la última respuesta se repite) y guarda los datos de cada llamada
    private static final class ScriptedHoldClient implements CatedraHoldClient {

        private final List<CatedraResponse<HoldResult>> script = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();

        ScriptedHoldClient then(CatedraResponse<HoldResult> response) {
            script.add(response);
            return this;
        }

        ScriptedHoldClient reset() {
            script.clear();
            calls.clear();
            return this;
        }

        List<String> calls() {
            return calls;
        }

        @Override
        public CatedraResponse<HoldResult> createHold(Long professionalId, LocalDate date, LocalTime startTime,
                                                      String externalPatientId, String technicalToken) {
            calls.add(professionalId + " " + date + " " + startTime + " " + externalPatientId + " " + technicalToken);
            return script.get(Math.min(calls.size(), script.size()) - 1);
        }

        @Override
        public CatedraResponse<HoldConfirmationResult> confirmHold(String holdId, String externalPatientId,
                                                                   String patientFirstName, String patientLastName,
                                                                   String technicalToken) {
            throw new UnsupportedOperationException("Creating a hold never confirms one");
        }
    }
}
