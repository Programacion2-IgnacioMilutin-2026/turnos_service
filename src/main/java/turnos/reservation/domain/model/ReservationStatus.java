package turnos.reservation.domain.model;

// Solo los estados que usa hasta ahora el bloqueo de turnos; cada caso de uso siguiente suma los suyos
public enum ReservationStatus {
    HELD,
    WAITING_FOR_PHONE,
    PHONE_SUBMITTED,
    EXPIRED;

    public boolean isOpen() {
        return this == HELD || this == WAITING_FOR_PHONE || this == PHONE_SUBMITTED;
    }
}
