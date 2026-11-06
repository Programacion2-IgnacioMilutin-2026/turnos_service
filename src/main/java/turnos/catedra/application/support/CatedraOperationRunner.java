package turnos.catedra.application.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.CatedraOperationPolicy;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.IntegrationState;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.in.RenewTechnicalSessionUseCase;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

// Paquete application/support: excepción a la skill autorizada por el usuario.
// No es un caso de uso: aplica a cualquier llamada de los casos de uso de catedra las reglas comunes de la cátedra
// (sesión técnica, JWT rechazado y reintentos). No se ofrece a otros slices, porque maneja el JWT técnico
@Slf4j
@Component
@RequiredArgsConstructor
public class CatedraOperationRunner {

    // Cada cuánto se mira si el login inicial terminó
    private static final Duration LOGIN_CHECK_INTERVAL = Duration.ofMillis(100);

    private final TechnicalSessionRepository technicalSessionRepository;
    private final RenewTechnicalSessionUseCase renewTechnicalSessionUseCase;
    private final CatedraOperationPolicy catedraOperationPolicy;

    // Con la sesión habilitada usa su JWT; degradada, pide un login antes; en error de configuración no llama.
    // Si la cátedra rechaza el JWT, renueva la sesión y repite una sola vez. Nunca devuelve TokenRejected
    public <T> CatedraResponse<T> run(String operation, Function<String, CatedraResponse<T>> call) {
        TechnicalSession session = technicalSessionRepository.get();
        if (session.state() == IntegrationState.CONFIGURATION_ERROR) {
            return new CatedraResponse.ConfigurationError<>("Technical integration has a configuration error");
        }
        if (!session.isEnabled()) {
            Optional<TechnicalSession> renewed = renewTechnicalSessionUseCase.renewTechnicalSession(session.token());
            if (renewed.isEmpty()) {
                return noSession();
            }
            session = renewed.get();
        }
        CatedraResponse<T> response = callWithRetries(operation, call, session.token());
        if (response instanceof CatedraResponse.TokenRejected<T>) {
            Optional<TechnicalSession> renewed = renewTechnicalSessionUseCase.renewTechnicalSession(session.token());
            if (renewed.isEmpty()) {
                return noSession();
            }
            response = callWithRetries(operation, call, renewed.get().token());
            if (response instanceof CatedraResponse.TokenRejected<T>) {
                log.warn("Catedra rejected the renewed technical token for the {}", operation);
                return new CatedraResponse.Unavailable<>("Catedra rejected the renewed technical token");
            }
        }
        if (response instanceof CatedraResponse.ConfigurationError<T> error) {
            log.error("Catedra {} configuration error: {}", operation, error.reason());
        }
        return response;
    }

    // Para una tarea que corre mientras el servicio arranca: si el login inicial está en curso, lo espera hasta el tope
    // en vez de responder enseguida que no hay sesión, y después sigue como run. Las operaciones de los usuarios usan
    // run: no deben quedar esperando al login
    public <T> CatedraResponse<T> runAfterInitialLogin(String operation, Function<String, CatedraResponse<T>> call) {
        awaitInitialLogin(operation);
        return run(operation, call);
    }

    private void awaitInitialLogin(String operation) {
        if (!isConnecting()) {
            return;
        }
        Duration maxWait = catedraOperationPolicy.initialLoginWait();
        long deadline = System.nanoTime() + maxWait.toNanos();
        log.info("Catedra {} waits for the initial technical login", operation);
        while (isConnecting()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                log.warn("Catedra {} stopped waiting: the initial technical login did not finish within {}", operation, maxWait);
                return;
            }
            if (!waitBefore(Duration.ofNanos(Math.min(remaining, LOGIN_CHECK_INTERVAL.toNanos())))) {
                return;
            }
        }
    }

    private boolean isConnecting() {
        return technicalSessionRepository.get().state() == IntegrationState.CONNECTING;
    }

    // Solo se repite lo que seguro no se procesó; cualquier otra respuesta se devuelve al primer intento
    private <T> CatedraResponse<T> callWithRetries(String operation, Function<String, CatedraResponse<T>> call, String token) {
        int maxAttempts = catedraOperationPolicy.maxAttempts();
        CatedraResponse<T> response = new CatedraResponse.Unavailable<>("Catedra request was not attempted");
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!waitBefore(catedraOperationPolicy.delayBeforeAttempt(attempt))) {
                break;
            }
            response = callSafely(operation, call, token);
            if (!(response instanceof CatedraResponse.Unavailable<T> unavailable)) {
                return response;
            }
            log.warn("Catedra {} attempt {}/{} failed: {}", operation, attempt, maxAttempts, unavailable.reason());
        }
        return response;
    }

    // Un error no previsto es un fallo propio, no una cátedra caída: no se repite
    private static <T> CatedraResponse<T> callSafely(String operation, Function<String, CatedraResponse<T>> call, String token) {
        try {
            return call.apply(token);
        } catch (RuntimeException e) {
            log.error("Unexpected error in the Catedra {}: {}", operation, e.getClass().getSimpleName());
            return new CatedraResponse.Failed<>("Unexpected error: " + e.getClass().getSimpleName());
        }
    }

    private static <T> CatedraResponse<T> noSession() {
        return new CatedraResponse.Unavailable<>("No technical session available");
    }

    private static boolean waitBefore(Duration delay) {
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
}
