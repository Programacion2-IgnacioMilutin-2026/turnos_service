package turnos.catedra.infrastructure.persistence.adapter;

import org.junit.jupiter.api.Test;
import turnos.catedra.domain.model.IntegrationState;
import turnos.catedra.domain.model.TechnicalSession;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTechnicalSessionRepositoryAdapterTest {

    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();

    @Test
    void startsConnecting() {
        assertThat(repository.get().state()).isEqualTo(IntegrationState.CONNECTING);
    }

    @Test
    void eachSaveReplacesTheWholeSession() {
        TechnicalSession degraded = TechnicalSession.degraded(Instant.parse("2026-10-21T12:00:00Z"));

        repository.save(degraded);

        assertThat(repository.get()).isSameAs(degraded);
    }
}
