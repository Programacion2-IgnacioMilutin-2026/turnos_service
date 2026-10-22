package turnos.integration.domain.ports.in;

import turnos.integration.domain.model.TechnicalSession;

import java.util.Optional;

public interface GetTechnicalSessionUseCase {
    Optional<TechnicalSession> getTechnicalSession();
}
