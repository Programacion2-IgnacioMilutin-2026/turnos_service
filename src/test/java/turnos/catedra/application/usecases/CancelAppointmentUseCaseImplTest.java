package turnos.catedra.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.MutableClock;
import turnos.catedra.application.support.CatedraOperationRunner;
import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.catedra.domain.model.CatedraOperationPolicy;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalLoginPolicy;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.out.CatedraAppointmentClient;
import turnos.catedra.infrastructure.persistence.adapter.InMemoryTechnicalSessionRepositoryAdapter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CancelAppointmentUseCaseImplTest {

    private static final Instant START = Instant.parse("2026-11-04T12:00:00Z");
    private static final AppointmentCancellationResult CANCELLED = new AppointmentCancellationResult.Cancelled(START);

    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();
    private final ScriptedLoginClient loginClient = new ScriptedLoginClient();
    private final ScriptedAppointmentClient client = new ScriptedAppointmentClient();
    private final CancelAppointmentUseCaseImpl useCase = new CancelAppointmentUseCaseImpl(client, new CatedraOperationRunner(
            repository,
            new RenewTechnicalSessionUseCaseImpl(loginClient, repository,
                    new TechnicalLoginPolicy(1, Duration.ofMillis(1), 2, Duration.ofMillis(1), Duration.ofSeconds(60)),
                    new MutableClock(START), integration -> {}),
            new CatedraOperationPolicy(3, Duration.ofMillis(1), 2)));

    @Test
    void theProcessAndTheReasonReachTheClientWithTheCurrentToken() {
        repository.save(enabled("jwt-a"));
        client.then(new CatedraResponse.Answered<>(CANCELLED));

        assertThat(useCase.cancelAppointment("process-1", "Cambio")).isEqualTo(CANCELLED);
        assertThat(client.calls()).containsExactly("process-1 Cambio jwt-a");
    }

    @Test
    void eachDefinitiveAnswerIsReturnedAsIsWithoutRepeating() {
        repository.save(enabled("jwt-a"));
        for (AppointmentCancellationResult answer : List.of(new AppointmentCancellationResult.NotFound(),
                new AppointmentCancellationResult.OwnerMismatch(), new AppointmentCancellationResult.NotCancellable())) {
            client.reset().then(new CatedraResponse.Answered<>(answer));

            assertThat(useCase.cancelAppointment("process-1", null)).isEqualTo(answer);
            assertThat(client.calls()).hasSize(1);
        }
    }

    @Test
    void aCatedraThatDoesNotAnswerIsRetriedUpToTheMaximum(CapturedOutput output) {
        repository.save(enabled("jwt-a"));
        client.then(new CatedraResponse.Unavailable<>("down"));

        assertThat(useCase.cancelAppointment("process-1", null)).isInstanceOf(AppointmentCancellationResult.Unavailable.class);
        assertThat(client.calls()).hasSize(3);
        assertThat(output.getAll()).contains("cancellation of process-1");
    }

    @Test
    void aLaterAttemptCanStillCancel() {
        repository.save(enabled("jwt-a"));
        client.then(new CatedraResponse.Unavailable<>("down")).then(new CatedraResponse.Answered<>(CANCELLED));

        assertThat(useCase.cancelAppointment("process-1", null)).isEqualTo(CANCELLED);
        assertThat(client.calls()).hasSize(2);
    }

    @Test
    void aRejectedTokenRenewsTheSessionAndRepeatsOnce() {
        repository.save(enabled("jwt-a"));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        client.then(new CatedraResponse.TokenRejected<>()).then(new CatedraResponse.Answered<>(CANCELLED));

        assertThat(useCase.cancelAppointment("process-1", null)).isEqualTo(CANCELLED);
        assertThat(client.calls()).containsExactly("process-1 null jwt-a", "process-1 null jwt-b");
    }

    @Test
    void aRenewedTokenRejectedAgainIsUnavailable() {
        repository.save(enabled("jwt-a"));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        client.then(new CatedraResponse.TokenRejected<>());

        assertThat(useCase.cancelAppointment("process-1", null)).isInstanceOf(AppointmentCancellationResult.Unavailable.class);
        assertThat(client.calls()).hasSize(2);
    }

    @Test
    void integrationProblemsKeepTheirMeaning() {
        repository.save(enabled("jwt-a"));

        client.then(new CatedraResponse.ConfigurationError<>("missing"));
        assertThat(useCase.cancelAppointment("process-1", null)).isEqualTo(new AppointmentCancellationResult.ConfigurationError("missing"));

        client.reset().then(new CatedraResponse.Failed<>("unexpected"));
        assertThat(useCase.cancelAppointment("process-1", null)).isEqualTo(new AppointmentCancellationResult.Failed("unexpected"));
        assertThat(client.calls()).hasSize(1);
    }

    @Test
    void aDegradedSessionWithoutAPossibleLoginIsUnavailable() {
        repository.save(TechnicalSession.degraded(START));

        assertThat(useCase.cancelAppointment("process-1", null)).isInstanceOf(AppointmentCancellationResult.Unavailable.class);
        assertThat(client.calls()).isEmpty();
    }

    // El último login fue hace dos minutos: ya pasó el intervalo mínimo para pedir otro
    private static TechnicalSession enabled(String token) {
        return TechnicalSession.enabled(token, ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED),
                START.minusSeconds(120));
    }

    // Responde en orden lo indicado (la última respuesta se repite) y guarda los datos de cada llamada
    private static final class ScriptedAppointmentClient implements CatedraAppointmentClient {

        private final List<CatedraResponse<AppointmentCancellationResult>> script = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();

        ScriptedAppointmentClient then(CatedraResponse<AppointmentCancellationResult> response) {
            script.add(response);
            return this;
        }

        ScriptedAppointmentClient reset() {
            script.clear();
            calls.clear();
            return this;
        }

        List<String> calls() {
            return calls;
        }

        @Override
        public CatedraResponse<AppointmentCancellationResult> cancelAppointment(String reservationProcessId, String reason,
                                                                                String technicalToken) {
            calls.add(reservationProcessId + " " + reason + " " + technicalToken);
            return script.get(Math.min(calls.size(), script.size()) - 1);
        }
    }
}
