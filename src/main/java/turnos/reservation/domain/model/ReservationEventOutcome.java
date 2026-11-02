package turnos.reservation.domain.model;

// Qué pasó con un evento de la cátedra. Solo APPLIED cambia el proceso
public enum ReservationEventOutcome {
    APPLIED,
    // Ya se había procesado: una nueva entrega del mismo evento no repite nada
    ALREADY_PROCESSED,
    // No hay un proceso local con ese identificador
    ORPHAN,
    // El hold del evento no es el del proceso local
    HOLD_MISMATCH,
    // El proceso ya avanzó o terminó: el evento no lo hace volver atrás
    STATE_ADVANCED
}
