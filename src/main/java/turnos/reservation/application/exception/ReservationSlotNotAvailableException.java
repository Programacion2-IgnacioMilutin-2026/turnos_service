package turnos.reservation.application.exception;

public class ReservationSlotNotAvailableException extends ReservationException {
    public ReservationSlotNotAvailableException(Long professionalId) {
        super("Slot is already reserved or held, professional " + professionalId);
    }
}
