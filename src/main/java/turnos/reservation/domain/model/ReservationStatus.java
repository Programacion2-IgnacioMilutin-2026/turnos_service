package turnos.reservation.domain.model;

public enum ReservationStatus {
    HELD,
    WAITING_FOR_PHONE,
    PHONE_SUBMITTED,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    INVALID;

    public boolean isOpen() {
        return this == HELD || this == WAITING_FOR_PHONE || this == PHONE_SUBMITTED;
    }
}
