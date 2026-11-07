package turnos.reservation.domain.model;

// El estado de una reserva en la cátedra. Una reserva sin teléfono todavía espera; las otras tres ya terminaron
public enum ReservationRecordStatus {
    PHONE_PENDING,
    CONFIRMED,
    CANCELLED,
    FAILED
}
