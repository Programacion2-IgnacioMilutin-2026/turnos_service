package turnos.catedra.domain.model;

// Qué pasó con un pedido de información adicional al entregarlo. Tipos inmutables: excepción a la skill autorizada
public sealed interface AdditionalInformationRequestResult {

    // Se guardó lo que el pedido cambia
    record Processed() implements AdditionalInformationRequestResult {
    }

    // No había nada que hacer con él (ya procesado, sin proceso, de otro hold o de un proceso que ya avanzó):
    // volver a entregarlo no cambiaría nada
    record Discarded(String reason) implements AdditionalInformationRequestResult {
    }

    // No se pudo guardar: hay que volver a entregarlo más tarde
    record NotSaved(String reason) implements AdditionalInformationRequestResult {
    }
}
