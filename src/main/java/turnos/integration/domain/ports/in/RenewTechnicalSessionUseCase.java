package turnos.integration.domain.ports.in;

import turnos.integration.domain.model.TechnicalSession;

import java.util.Optional;

public interface RenewTechnicalSessionUseCase {
    Optional<TechnicalSession> renewTechnicalSession(String rejectedToken);
}
