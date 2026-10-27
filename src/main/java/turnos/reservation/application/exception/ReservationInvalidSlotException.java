package turnos.reservation.application.exception;

public class ReservationInvalidSlotException extends ReservationException {
    public ReservationInvalidSlotException(Long professionalId) {
        super("Slot is not part of an enabled schedule of professional " + professionalId);
    }
}
