package turnos.catedra.domain.model;

// Qué pasó al enviar la respuesta a la cátedra. Tipos inmutables: excepción a la skill autorizada
public sealed interface AdditionalInformationSubmissionResult {

    record Published() implements AdditionalInformationSubmissionResult {
    }

    // No hay confirmación de que haya salido; un intento vencido pudo haber llegado igual
    record NotPublished(String reason) implements AdditionalInformationSubmissionResult {
    }
}
