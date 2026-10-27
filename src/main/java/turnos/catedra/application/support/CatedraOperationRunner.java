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
