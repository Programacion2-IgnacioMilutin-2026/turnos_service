package turnos.integration.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import turnos.integration.domain.model.TechnicalSession;
import turnos.integration.domain.ports.in.GetTechnicalSessionUseCase;
import turnos.integration.domain.ports.in.RenewTechnicalSessionUseCase;
import turnos.integration.domain.ports.in.StartTechnicalSessionUseCase;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TechnicalSessionService {

    private final StartTechnicalSessionUseCase startTechnicalSessionUseCase;
    private final GetTechnicalSessionUseCase getTechnicalSessionUseCase;
    private final RenewTechnicalSessionUseCase renewTechnicalSessionUseCase;

    public TechnicalSession startTechnicalSession() {
        return startTechnicalSessionUseCase.startTechnicalSession();
    }

    public Optional<TechnicalSession> getTechnicalSession() {
        return getTechnicalSessionUseCase.getTechnicalSession();
    }

    public Optional<TechnicalSession> renewTechnicalSession(String rejectedToken) {
        return renewTechnicalSessionUseCase.renewTechnicalSession(rejectedToken);
    }

}
