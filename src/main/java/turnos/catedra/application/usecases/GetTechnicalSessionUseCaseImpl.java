package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.in.GetTechnicalSessionUseCase;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

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
