package turnos.catedra.domain.model;

// Los resultados posibles de pedirle un hold a la cátedra. Tipos inmutables: excepción a la skill autorizada.
// Los primeros cinco son respuestas definitivas de la cátedra; los otros tres, problemas de la integración
public sealed interface HoldResult {

    record Created(Hold hold) implements HoldResult {
    }

    record ProfessionalNotFound() implements HoldResult {
    }

    record ProfessionalDisabled() implements HoldResult {
    }

    // El turno no pertenece a un horario habilitado del profesional
    record InvalidSlot() implements HoldResult {
    }

    // El turno ya está reservado o retenido por otro
    record SlotNotAvailable() implements HoldResult {
    }

    record ConfigurationError(String reason) implements HoldResult {
    }

    // La cátedra no respondió, o no se sabe si llegó a crear el hold
    record Unavailable(String reason) implements HoldResult {
    }

    // Una respuesta que no debería ocurrir: repetir no la arregla
    record Failed(String reason) implements HoldResult {
    }
}
