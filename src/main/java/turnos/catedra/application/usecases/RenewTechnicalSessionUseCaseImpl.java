package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.TechnicalLoginPolicy;
import turnos.catedra.domain.model.TechnicalLoginResult;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.in.RenewTechnicalSessionUseCase;
import turnos.catedra.domain.ports.out.TechnicalLoginClient;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class RenewTechnicalSessionUseCaseImpl implements RenewTechnicalSessionUseCase {

    private final TechnicalLoginClient technicalLoginClient;
    private final TechnicalSessionRepository technicalSessionRepository;
    private final TechnicalLoginPolicy technicalLoginPolicy;
    private final Clock clock;
    // Un solo login a la vez: quien llega mientras hay uno en curso espera y después usa su resultado
    private final ReentrantLock loginLock = new ReentrantLock();

    // Devuelve una sesión con un JWT distinto del rechazado, o vacío si no se pudo conseguir uno
    @Override
    public Optional<TechnicalSession> renewTechnicalSession(String rejectedToken) {
        loginLock.lock();
        try {
            TechnicalSession current = technicalSessionRepository.get();
            if (current.isEnabled() && !Objects.equals(current.token(), rejectedToken)) {
                return Optional.of(current);
            }
            if (!current.canAttemptLogin(clock.instant(), technicalLoginPolicy.minInterval())) {
                return Optional.empty();
            }
            TechnicalSession renewed = attemptLogin();
            technicalSessionRepository.save(renewed);
            return renewed.isEnabled() ? Optional.of(renewed) : Optional.empty();
        } finally {
            loginLock.unlock();
        }
    }

    private TechnicalSession attemptLogin() {
        Instant attemptAt = clock.instant();
        TechnicalLoginResult result = loginSafely();
        return switch (result) {
            case TechnicalLoginResult.Success success when success.integration().isProvisioned() -> {
                log.info("Technical login renewed: groupId={}, provisioningStatus={}",
                        success.integration().groupId(), success.integration().provisioningStatus());
                yield TechnicalSession.enabled(success.token(), success.integration(), attemptAt);
            }
            case TechnicalLoginResult.Success success -> {
                log.warn("Technical login renewal: integration not enabled, provisioningStatus={}",
                        success.integration().provisioningStatus());
                yield TechnicalSession.degraded(attemptAt);
            }
            case TechnicalLoginResult.ConfigurationError error -> {
                log.error("Technical login configuration error, no retries until restart: {}", error.reason());
                yield TechnicalSession.configurationError(attemptAt);
            }
            case TechnicalLoginResult.Unavailable unavailable -> {
                log.warn("Technical login renewal failed: {}", unavailable.reason());
                yield TechnicalSession.degraded(attemptAt);
            }
        };
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
