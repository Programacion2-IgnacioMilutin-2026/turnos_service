package turnos.catedra.domain.model;

// Los resultados posibles de confirmar un hold en la cátedra. Tipos inmutables: excepción a la skill autorizada.
// Los primeros nueve son respuestas definitivas de la cátedra; los otros tres, problemas de la integración
public sealed interface HoldConfirmationResult {

    record Confirmed(HoldConfirmation confirmation) implements HoldConfirmationResult {
    }

    // El proceso ya se había confirmado antes: no es un error, pero la cátedra no informa su vencimiento
    record AlreadyConfirmed() implements HoldConfirmationResult {
    }

    // La cátedra no conoce el hold; un hold vencido también responde así
    record HoldNotFound() implements HoldConfirmationResult {
    }

    record HoldExpired() implements HoldConfirmationResult {
    }

    // El hold es de otra cuenta técnica: no debería ocurrir, porque los dos backends comparten la misma
    record OwnerMismatch() implements HoldConfirmationResult {
    }

    // El paciente no es el que pidió el hold
    record PatientMismatch() implements HoldConfirmationResult {
    }

    record ProfessionalNotFound() implements HoldConfirmationResult {
    }

    record InvalidSlot() implements HoldConfirmationResult {
    }

    // El turno ya está reservado por otro
    record SlotNotAvailable() implements HoldConfirmationResult {
    }

    record ConfigurationError(String reason) implements HoldConfirmationResult {
    }

    // La cátedra no respondió después de los reintentos
    record Unavailable(String reason) implements HoldConfirmationResult {
    }

    // Una respuesta que no debería ocurrir: repetir no la arregla
    record Failed(String reason) implements HoldConfirmationResult {
    }
}
