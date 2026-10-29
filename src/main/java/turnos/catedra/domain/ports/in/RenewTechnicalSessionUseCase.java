package turnos.catedra.domain.ports.in;

import turnos.catedra.domain.model.TechnicalSession;

import java.util.Optional;

public interface RenewTechnicalSessionUseCase {
    Optional<TechnicalSession> renewTechnicalSession(String rejectedToken);
}
