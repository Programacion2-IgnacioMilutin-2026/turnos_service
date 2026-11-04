package turnos.catedra.domain.model;

import java.time.Instant;

// Los resultados posibles de cancelar una reserva en la cátedra. Tipos inmutables: excepción a la skill autorizada.
// Los primeros cuatro son respuestas definitivas de la cátedra; los otros tres, problemas de la integración
public sealed interface AppointmentCancellationResult {

    record Cancelled(Instant cancelledAt) implements AppointmentCancellationResult {
    }

    record NotFound() implements AppointmentCancellationResult {
    }

    // La reserva es de otra cuenta técnica: no debería ocurrir, porque los dos backends comparten la cuenta
    record OwnerMismatch() implements AppointmentCancellationResult {
    }

    record NotCancellable() implements AppointmentCancellationResult {
    }

    record ConfigurationError(String reason) implements AppointmentCancellationResult {
    }

    record Unavailable(String reason) implements AppointmentCancellationResult {
    }

    record Failed(String reason) implements AppointmentCancellationResult {
    }
}
