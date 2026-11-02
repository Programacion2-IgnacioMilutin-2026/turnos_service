package turnos.catedra.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.in.RenewTechnicalSessionUseCase;
import turnos.catedra.infrastructure.persistence.adapter.InMemoryTechnicalSessionRepositoryAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CheckTechnicalIntegrationUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-11-02T13:00:00Z");
    private static final TechnicalSession ENABLED = TechnicalSession.enabled("jwt-a",
            ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED), NOW);

    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();
    private final List<String> renewals = new ArrayList<>();

    private CheckTechnicalIntegrationUseCaseImpl useCase(Optional<TechnicalSession> renewed) {
        RenewTechnicalSessionUseCase renew = rejectedToken -> {
            renewals.add(String.valueOf(rejectedToken));
            return renewed;
        };
        return new CheckTechnicalIntegrationUseCaseImpl(repository, renew);
    }

    @Test
    void anEnabledSessionIsEnoughWithoutALogin() {
        repository.save(ENABLED);

        assertThat(useCase(Optional.empty()).checkTechnicalIntegration()).isTrue();

        assertThat(renewals).isEmpty();
    }

    @Test
    void withoutASessionItTriesToGetOne() {
        repository.save(TechnicalSession.degraded(NOW));

        assertThat(useCase(Optional.of(ENABLED)).checkTechnicalIntegration()).isTrue();

        assertThat(renewals).containsExactly("null");
    }

    @Test
    void whenNoSessionCanBeObtainedTheReasonIsInTheLog(CapturedOutput output) {
        repository.save(TechnicalSession.degraded(NOW));

        assertThat(useCase(Optional.empty()).checkTechnicalIntegration()).isFalse();

        assertThat(output.getAll()).contains("WARN").contains("no technical session could be obtained");
    }

    @Test
    void whileConnectingWithoutASessionItIsNotAvailable() {
        repository.save(TechnicalSession.connecting());

        assertThat(useCase(Optional.empty()).checkTechnicalIntegration()).isFalse();
    }

    @Test
    void aConfigurationErrorDoesNotTryALoginAndIsAnErrorInTheLog(CapturedOutput output) {
        repository.save(TechnicalSession.configurationError(NOW));

        assertThat(useCase(Optional.of(ENABLED)).checkTechnicalIntegration()).isFalse();

        assertThat(renewals).isEmpty();
        assertThat(output.getAll()).contains("ERROR").contains("it has a configuration error");
    }
}
