package turnos.catedra.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.in.GetTechnicalSessionUseCase;
import turnos.catedra.domain.ports.in.RenewTechnicalSessionUseCase;
import turnos.catedra.domain.ports.in.StartTechnicalSessionUseCase;

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
