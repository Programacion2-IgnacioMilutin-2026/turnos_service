package turnos.catedra.application.usecases;

import turnos.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.catedra.application.support.CatedraOperationRunner;
import turnos.catedra.domain.model.CatedraOperationPolicy;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.OccupanciesResult;
import turnos.catedra.domain.model.Occupancy;
import turnos.catedra.domain.model.OccupancyStatus;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalLoginPolicy;
import turnos.catedra.domain.model.TechnicalLoginResult;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.out.CatedraOccupancyClient;
import turnos.catedra.infrastructure.persistence.adapter.InMemoryTechnicalSessionRepositoryAdapter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GetOccupanciesUseCaseImplTest {

    private static final Instant START = Instant.parse("2026-10-25T12:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 26);
    private static final List<Occupancy> OCCUPANCIES = List.of(
            Occupancy.builder().startTime(LocalTime.of(12, 0)).status(OccupancyStatus.CONFIRMED).build());
    private static final OccupanciesResult FOUND = new OccupanciesResult.Found(OCCUPANCIES);
    private static final CatedraResponse<List<Occupancy>> ANSWERED = new CatedraResponse.Answered<>(OCCUPANCIES);
    private static final CatedraResponse<List<Occupancy>> UNAVAILABLE = new CatedraResponse.Unavailable<>("Catedra answered HTTP 503");

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();
    private final ScriptedLoginClient loginClient = new ScriptedLoginClient();
    private final ScriptedOccupancyClient occupancyClient = new ScriptedOccupancyClient();
    private final RenewTechnicalSessionUseCaseImpl renew = new RenewTechnicalSessionUseCaseImpl(loginClient, repository,
            new TechnicalLoginPolicy(1, Duration.ofMillis(1), 2, Duration.ofMillis(1), Duration.ofSeconds(60)), clock);
    private final GetOccupanciesUseCaseImpl useCase = new GetOccupanciesUseCaseImpl(occupancyClient,
            new CatedraOperationRunner(repository, renew, new CatedraOperationPolicy(3, Duration.ofMillis(1), 2)));

    @Test
    void anEnabledSessionUsesItsCurrentToken() {
        repository.save(enabled("jwt-a"));
        occupancyClient.then(ANSWERED);

        assertThat(useCase.getOccupancies(201L, DATE)).isEqualTo(FOUND);
        assertThat(occupancyClient.tokens()).containsExactly("jwt-a");
        assertThat(occupancyClient.calls()).containsExactly("201 " + DATE);
        assertThat(loginClient.calls()).isZero();
    }

    @Test
    void aConfigurationErrorAnswersWithoutCallingTheCatedra() {
        repository.save(TechnicalSession.configurationError(START));

        assertThat(useCase.getOccupancies(201L, DATE)).isInstanceOf(OccupanciesResult.ConfigurationError.class);
        assertThat(occupancyClient.tokens()).isEmpty();
        assertThat(loginClient.calls()).isZero();
    }

    @Test
    void aDegradedSessionLogsInFirstAndUsesTheNewToken() {
        repository.save(TechnicalSession.degraded(START.minusSeconds(120)));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        occupancyClient.then(ANSWERED);

        assertThat(useCase.getOccupancies(201L, DATE)).isEqualTo(FOUND);
        assertThat(loginClient.calls()).isEqualTo(1);
        assertThat(occupancyClient.tokens()).containsExactly("jwt-b");
    }

    @Test
    void aDegradedSessionWithoutAPossibleLoginIsUnavailable() {
        repository.save(TechnicalSession.degraded(START.minusSeconds(120)));
        loginClient.then(new TechnicalLoginResult.Unavailable("Catedra answered HTTP 503"));

        assertThat(useCase.getOccupancies(201L, DATE)).isInstanceOf(OccupanciesResult.Unavailable.class);
        assertThat(occupancyClient.tokens()).isEmpty();
    }

    @Test
    void aSessionStillConnectingIsUnavailableWithoutAnotherLogin() {
        repository.save(TechnicalSession.connecting());

        assertThat(useCase.getOccupancies(201L, DATE)).isInstanceOf(OccupanciesResult.Unavailable.class);
        assertThat(loginClient.calls()).isZero();
        assertThat(occupancyClient.tokens()).isEmpty();
    }

    @Test
    void aRejectedTokenRenewsTheSessionAndRepeatsOnceWithTheNewToken() {
        repository.save(enabled("jwt-a"));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        occupancyClient.then(new CatedraResponse.TokenRejected<>()).then(ANSWERED);

        assertThat(useCase.getOccupancies(201L, DATE)).isEqualTo(FOUND);
        assertThat(loginClient.calls()).isEqualTo(1);
        assertThat(occupancyClient.tokens()).containsExactly("jwt-a", "jwt-b");
    }

    @Test
    void aSecondRejectionIsNotRepeatedAgainAndIsUnavailable() {
        repository.save(enabled("jwt-a"));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        occupancyClient.then(new CatedraResponse.TokenRejected<>());

        assertThat(useCase.getOccupancies(201L, DATE)).isInstanceOf(OccupanciesResult.Unavailable.class);
        assertThat(loginClient.calls()).isEqualTo(1);
        assertThat(occupancyClient.tokens()).containsExactly("jwt-a", "jwt-b");
    }

    @Test
    void aRejectedTokenWithoutARenewedSessionIsUnavailable() {
        repository.save(enabled("jwt-a"));
        loginClient.then(new TechnicalLoginResult.Unavailable("Catedra is not reachable"));
        occupancyClient.then(new CatedraResponse.TokenRejected<>());

        assertThat(useCase.getOccupancies(201L, DATE)).isInstanceOf(OccupanciesResult.Unavailable.class);
        assertThat(occupancyClient.tokens()).containsExactly("jwt-a");
    }

    @Test
    void aMissingStudentIntegrationIsAConfigurationErrorWithoutRetrying(CapturedOutput output) {
        repository.save(enabled("jwt-a"));
        occupancyClient.then(new CatedraResponse.ConfigurationError<>("Catedra answered STUDENT_INTEGRATION_NOT_FOUND"));

        assertThat(useCase.getOccupancies(201L, DATE)).isInstanceOf(OccupanciesResult.ConfigurationError.class);
        assertThat(occupancyClient.tokens()).hasSize(1);
        assertThat(output.getAll()).contains("ERROR").contains("STUDENT_INTEGRATION_NOT_FOUND");
    }

    @Test
    void anUnavailableCatedraIsRetriedUpToTheMaximum() {
        repository.save(enabled("jwt-a"));
        occupancyClient.then(UNAVAILABLE);

        assertThat(useCase.getOccupancies(201L, DATE)).isInstanceOf(OccupanciesResult.Unavailable.class);
        assertThat(occupancyClient.tokens()).hasSize(3);
    }

    @Test
    void aLaterSuccessfulAttemptEndsTheRetries() {
        repository.save(enabled("jwt-a"));
        occupancyClient.then(UNAVAILABLE).then(ANSWERED);

        assertThat(useCase.getOccupancies(201L, DATE)).isEqualTo(FOUND);
        assertThat(occupancyClient.tokens()).hasSize(2);
    }

    @Test
    void anUnexpectedAnswerIsAFailureWithoutRetrying() {
        repository.save(enabled("jwt-a"));
        occupancyClient.then(new CatedraResponse.Failed<>("Catedra answered HTTP 400"));

        assertThat(useCase.getOccupancies(201L, DATE)).isInstanceOf(OccupanciesResult.Failed.class);
        assertThat(occupancyClient.tokens()).hasSize(1);
    }

    @Test
    void aQueryThatMayHaveBeenProcessedIsUnavailableWithoutRetrying() {
        repository.save(enabled("jwt-a"));
        occupancyClient.then(new CatedraResponse.Uncertain<>("Catedra answered HTTP 500"));

        assertThat(useCase.getOccupancies(201L, DATE)).isInstanceOf(OccupanciesResult.Unavailable.class);
        assertThat(occupancyClient.tokens()).hasSize(1);
    }

    @Test
    void anUnexpectedErrorIsAFailureWithoutRetrying() {
        repository.save(enabled("jwt-a"));
        occupancyClient.then(() -> {
            throw new IllegalStateException("broken client");
        });

        assertThat(useCase.getOccupancies(201L, DATE)).isInstanceOf(OccupanciesResult.Failed.class);
        assertThat(occupancyClient.tokens()).hasSize(1);
    }

    @Test
    void eachFailedAttemptIsLoggedWithoutTheToken(CapturedOutput output) {
        repository.save(enabled("jwt-secreto-a"));
        occupancyClient.then(UNAVAILABLE);

        useCase.getOccupancies(201L, DATE);

        assertThat(output.getAll())
                .contains("Catedra occupancies request for professional 201 attempt 1/3 failed: Catedra answered HTTP 503")
                .contains("attempt 3/3 failed")
                .doesNotContain("jwt-secreto-a");
    }

    // El último login fue hace dos minutos: ya pasó el intervalo mínimo para pedir otro
    private static TechnicalSession enabled(String token) {
        return TechnicalSession.enabled(token, ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED),
                START.minusSeconds(120));
    }

    // Responde en orden lo indicado (la última respuesta se repite) y guarda cada consulta y el token usado
    private static final class ScriptedOccupancyClient implements CatedraOccupancyClient {

        private final List<Supplier<CatedraResponse<List<Occupancy>>>> script = new ArrayList<>();
        private final List<String> tokens = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();

        ScriptedOccupancyClient then(CatedraResponse<List<Occupancy>> result) {
            return then(() -> result);
        }

        ScriptedOccupancyClient then(Supplier<CatedraResponse<List<Occupancy>>> result) {
            script.add(result);
            return this;
        }

        List<String> tokens() {
            return tokens;
        }

        List<String> calls() {
            return calls;
        }

        @Override
        public CatedraResponse<List<Occupancy>> fetchOccupancies(Long professionalId, LocalDate date, String technicalToken) {
            tokens.add(technicalToken);
            calls.add(professionalId + " " + date);
            return script.get(Math.min(tokens.size(), script.size()) - 1).get();
        }
    }
}
