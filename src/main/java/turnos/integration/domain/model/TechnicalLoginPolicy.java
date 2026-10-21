package turnos.integration.domain.model;

import java.time.Duration;

// Cuántas veces y con qué esperas se intenta el login, y el intervalo mínimo entre logins a pedido
public record TechnicalLoginPolicy(
        int maxAttempts,
        Duration initialDelay,
        double multiplier,
        Duration maxDelay,
        Duration minInterval) {

    // El primer intento no espera; desde el segundo, la espera crece con el multiplicador hasta el tope
    public Duration delayBeforeAttempt(int attempt) {
        if (attempt <= 1) {
            return Duration.ZERO;
        }
        double millis = initialDelay.toMillis() * Math.pow(multiplier, attempt - 2.0);
        return Duration.ofMillis((long) Math.min(millis, maxDelay.toMillis()));
    }
}
