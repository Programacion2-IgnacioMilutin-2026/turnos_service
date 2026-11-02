package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.IntegrationState;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.in.CheckTechnicalIntegrationUseCase;
import turnos.catedra.domain.ports.in.RenewTechnicalSessionUseCase;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class CheckTechnicalIntegrationUseCaseImpl implements CheckTechnicalIntegrationUseCase {

    private final TechnicalSessionRepository technicalSessionRepository;
    private final RenewTechnicalSessionUseCase renewTechnicalSessionUseCase;

    // Un error de configuración no se arregla con otro login. La renovación ya respeta su intervalo mínimo
    @Override
    public boolean checkTechnicalIntegration() {
        TechnicalSession session = technicalSessionRepository.get();
        if (session.isEnabled()) {
            return true;
        }
        if (session.state() == IntegrationState.CONFIGURATION_ERROR) {
            log.error("Technical integration is not available: it has a configuration error");
            return false;
        }
        if (renewTechnicalSessionUseCase.renewTechnicalSession(session.token()).isPresent()) {
            return true;
        }
        log.warn("Technical integration is not available: no technical session could be obtained");
        return false;
    }
}
