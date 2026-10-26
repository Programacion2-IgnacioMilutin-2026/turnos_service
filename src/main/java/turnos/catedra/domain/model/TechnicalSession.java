package turnos.catedra.domain.model;

import java.time.Duration;
import java.time.Instant;

// record inmutable en lugar de clase Lombok: excepción a la skill autorizada por el usuario.
// Cada cambio de estado crea una sesión nueva que reemplaza entera a la anterior
public record TechnicalSession(
        IntegrationState state,
        String token,
        TechnicalIntegration integration,
        Instant lastAttemptAt) {

    public static TechnicalSession connecting() {
        return new TechnicalSession(IntegrationState.CONNECTING, null, null, null);
    }

    public static TechnicalSession enabled(String token, TechnicalIntegration integration, Instant attemptAt) {
        return new TechnicalSession(IntegrationState.ENABLED, token, integration, attemptAt);
    }

    public static TechnicalSession degraded(Instant attemptAt) {
        return new TechnicalSession(IntegrationState.DEGRADED, null, null, attemptAt);
    }

    public static TechnicalSession configurationError(Instant attemptAt) {
        return new TechnicalSession(IntegrationState.CONFIGURATION_ERROR, null, null, attemptAt);
    }

    public boolean isEnabled() {
        return state == IntegrationState.ENABLED;
    }

    // Error de configuración: nunca más hasta reiniciar. Conectando: el login inicial ya está en curso.
    // Habilitada o degradada: solo si pasó el intervalo mínimo desde el último intento
    public boolean canAttemptLogin(Instant now, Duration minInterval) {
        return switch (state) {
            case CONFIGURATION_ERROR, CONNECTING -> false;
            case ENABLED, DEGRADED -> lastAttemptAt == null || !now.isBefore(lastAttemptAt.plus(minInterval));
        };
    }

    // El JWT técnico no debe aparecer en logs
    @Override
    public String toString() {
        return "TechnicalSession[state=" + state + ", token=" + (token == null ? "none" : "***")
                + ", integration=" + integration + ", lastAttemptAt=" + lastAttemptAt + "]";
    }
}
