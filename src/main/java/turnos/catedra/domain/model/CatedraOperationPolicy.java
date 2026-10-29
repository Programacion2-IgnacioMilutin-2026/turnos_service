package turnos.catedra.domain.model;

import java.time.Duration;

// Cuántas veces y con qué esperas se intenta una operación con la cátedra que no respondió
public record CatedraOperationPolicy(int maxAttempts, Duration initialDelay, double multiplier) {

    // El primer intento no espera; desde el segundo, la espera crece con el multiplicador
    public Duration delayBeforeAttempt(int attempt) {
        if (attempt <= 1) {
            return Duration.ZERO;
        }
        return Duration.ofMillis((long) (initialDelay.toMillis() * Math.pow(multiplier, attempt - 2.0)));
    }
}
