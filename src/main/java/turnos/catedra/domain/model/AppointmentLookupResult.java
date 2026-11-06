package turnos.catedra.domain.model;

// Lo que se encontró al buscar en la cátedra la reserva de un proceso. Tipos inmutables: excepción a la skill autorizada.
// Los dos primeros son respuestas de la cátedra; los otros tres, problemas de la integración
public sealed interface AppointmentLookupResult {

    record Found(Appointment appointment) implements AppointmentLookupResult {
    }

    // La cátedra no tiene una reserva de ese paciente para el proceso
    record NotFound() implements AppointmentLookupResult {
    }

    record ConfigurationError(String reason) implements AppointmentLookupResult {
    }

    record Unavailable(String reason) implements AppointmentLookupResult {
    }

    // La reserva buscada vino incompleta o la cátedra respondió algo no previsto: repetir no lo arregla
    record Failed(String reason) implements AppointmentLookupResult {
    }
}
