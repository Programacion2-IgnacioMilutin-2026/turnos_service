package turnos.catedra.domain.model;

// Qué pasó con un evento de la cátedra al entregarlo. Tipos inmutables: excepción a la skill autorizada
public sealed interface CatedraEventResult {

    // Se guardó lo que el evento cambia
    record Processed() implements CatedraEventResult {
    }

    // No había nada que hacer con él (ya procesado, sin proceso, de otro hold o de un proceso que ya avanzó):
    // volver a entregarlo no cambiaría nada
    record Discarded(String reason) implements CatedraEventResult {
    }

    // No se pudo guardar: hay que volver a entregarlo más tarde
    record NotSaved(String reason) implements CatedraEventResult {
    }
}
