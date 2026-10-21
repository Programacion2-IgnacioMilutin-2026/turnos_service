package turnos.integration.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.integration.domain.model.TechnicalLoginPolicy;
import turnos.integration.domain.model.TechnicalLoginResult;
import turnos.integration.domain.model.TechnicalSession;
import turnos.integration.domain.ports.in.StartTechnicalSessionUseCase;
import turnos.integration.domain.ports.out.TechnicalLoginClient;
import turnos.integration.domain.ports.out.TechnicalSessionRepository;

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
                    return save(TechnicalSession.enabled(success.token(), success.integration(), clock.instant()));
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
