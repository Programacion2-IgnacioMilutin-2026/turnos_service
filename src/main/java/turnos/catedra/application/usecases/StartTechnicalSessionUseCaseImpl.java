package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.TechnicalLoginPolicy;
import turnos.catedra.domain.model.TechnicalLoginResult;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.in.StartTechnicalSessionUseCase;
import turnos.catedra.domain.ports.out.TechnicalIntegrationListener;
import turnos.catedra.domain.ports.out.TechnicalLoginClient;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.time.Clock;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartTechnicalSessionUseCaseImpl implements StartTechnicalSessionUseCase {

    private final TechnicalLoginClient technicalLoginClient;
    private final TechnicalSessionRepository technicalSessionRepository;
    private final TechnicalLoginPolicy technicalLoginPolicy;
    private final Clock clock;
    private final TechnicalIntegrationListener technicalIntegrationListener;

    // Mientras dure, la sesión está en CONNECTING y los logins a pedido se rechazan: nunca coincide con una renovación
    @Override
    public TechnicalSession startTechnicalSession() {
        technicalSessionRepository.save(TechnicalSession.connecting());
        int maxAttempts = technicalLoginPolicy.maxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!waitBefore(attempt)) {
                break;
            }
            TechnicalLoginResult result = loginSafely();
            switch (result) {
                case TechnicalLoginResult.Success success when success.integration().isProvisioned() -> {
                    log.info("Technical login succeeded: groupId={}, provisioningStatus={}",
                            success.integration().groupId(), success.integration().provisioningStatus());
                    TechnicalSession enabled = save(TechnicalSession.enabled(success.token(), success.integration(), clock.instant()));
                    notifyEnabled(enabled);
                    return enabled;
                }
                case TechnicalLoginResult.Success success -> log.warn(
                        "Technical login attempt {}/{}: integration not enabled, provisioningStatus={}",
                        attempt, maxAttempts, success.integration().provisioningStatus());
                case TechnicalLoginResult.ConfigurationError error -> {
                    log.error("Technical login configuration error, no retries until restart: {}", error.reason());
                    return save(TechnicalSession.configurationError(clock.instant()));
                }
                case TechnicalLoginResult.Unavailable unavailable -> log.warn(
                        "Technical login attempt {}/{} failed: {}", attempt, maxAttempts, unavailable.reason());
            }
        }
        log.warn("Technical login not completed after {} attempts: integration degraded", maxAttempts);
        return save(TechnicalSession.degraded(clock.instant()));
    }

    private boolean waitBefore(int attempt) {
        Duration delay = technicalLoginPolicy.delayBeforeAttempt(attempt);
        if (delay.isZero()) {
            return true;
        }
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // La sesión ya quedó guardada: si quien recibe el aviso falla, el login no se deshace
    private void notifyEnabled(TechnicalSession session) {
        try {
            technicalIntegrationListener.integrationEnabled(session.integration());
        } catch (RuntimeException e) {
            log.error("The enabled integration could not be notified: {}", e.getClass().getSimpleName());
        }
    }

    private TechnicalSession save(TechnicalSession technicalSession) {
        technicalSessionRepository.save(technicalSession);
        return technicalSession;
    }

    // Si el cliente falla de una forma no prevista, cuenta como cátedra no disponible: la sesión nunca queda trabada
    private TechnicalLoginResult loginSafely() {
        try {
            return technicalLoginClient.login();
        } catch (RuntimeException e) {
            log.error("Unexpected error during the technical login: {}", e.getClass().getSimpleName());
            return new TechnicalLoginResult.Unavailable("Unexpected error: " + e.getClass().getSimpleName());
        }
    }

}
