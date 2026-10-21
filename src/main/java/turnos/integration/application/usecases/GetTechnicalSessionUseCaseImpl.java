package turnos.integration.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.integration.domain.model.TechnicalSession;
import turnos.integration.domain.ports.in.GetTechnicalSessionUseCase;
import turnos.integration.domain.ports.out.TechnicalSessionRepository;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class GetTechnicalSessionUseCaseImpl implements GetTechnicalSessionUseCase {

    private final TechnicalSessionRepository technicalSessionRepository;

    // Lee la sesión vigente en cada llamada y nunca contacta a la cátedra
    @Override
    public Optional<TechnicalSession> getTechnicalSession() {
        TechnicalSession current = technicalSessionRepository.get();
        return current.isEnabled() ? Optional.of(current) : Optional.empty();
    }

}
