package turnos.catedra.application.usecases;

import org.junit.jupiter.api.Test;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.infrastructure.persistence.adapter.InMemoryTechnicalSessionRepositoryAdapter;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GetTechnicalSessionUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();
    private final GetTechnicalSessionUseCaseImpl useCase = new GetTechnicalSessionUseCaseImpl(repository);

    @Test
    void anEnabledSessionIsReturnedAsItIs() {
        TechnicalSession enabled = TechnicalSession.enabled("jwt-a",
                ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED), NOW);
        repository.save(enabled);

        assertThat(useCase.getTechnicalSession()).containsSame(enabled);
    }

    @Test
    void alwaysReadsTheCurrentSession() {
        repository.save(TechnicalSession.enabled("jwt-a", ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED), NOW));
        assertThat(useCase.getTechnicalSession()).map(TechnicalSession::token).contains("jwt-a");

        repository.save(TechnicalSession.enabled("jwt-b", ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED), NOW));

        assertThat(useCase.getTechnicalSession()).map(TechnicalSession::token).contains("jwt-b");
    }

    @Test
    void aSessionThatIsNotEnabledIsNotReturned() {
        repository.save(TechnicalSession.connecting());
        assertThat(useCase.getTechnicalSession()).isEmpty();

        repository.save(TechnicalSession.degraded(NOW));
        assertThat(useCase.getTechnicalSession()).isEmpty();

        repository.save(TechnicalSession.configurationError(NOW));
        assertThat(useCase.getTechnicalSession()).isEmpty();
    }
}
