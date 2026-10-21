package turnos.integration.domain.model;

// Los tres resultados posibles de un intento de login técnico. Tipos inmutables: excepción a la skill autorizada
public sealed interface TechnicalLoginResult {

    record Success(String token, TechnicalIntegration integration) implements TechnicalLoginResult {

        // El JWT técnico no debe aparecer en logs
        @Override
        public String toString() {
            return "Success[token=***, integration=" + integration + "]";
        }
    }

    // Credenciales rechazadas, integración inexistente o configuración faltante: repetir no lo arregla
    record ConfigurationError(String reason) implements TechnicalLoginResult {
    }

    // Timeout, conexión rechazada o error del servidor de la cátedra: puede arreglarse solo
    record Unavailable(String reason) implements TechnicalLoginResult {
    }
}
