package turnos.reservation.domain.model;

// Qué pasó con un pedido de teléfono de la cátedra. Solo APPLIED cambia el proceso y registra el pedido como procesado
public enum PhoneRequestOutcome {
    APPLIED,
    // Ya se había procesado: una nueva entrega del mismo pedido no repite nada
    ALREADY_PROCESSED,
    // No hay un proceso local con ese identificador
    ORPHAN,
    // El hold del pedido no es el del proceso local
    HOLD_MISMATCH,
    // El proceso ya avanzó o terminó: el pedido no lo hace volver atrás
    STATE_ADVANCED
}
