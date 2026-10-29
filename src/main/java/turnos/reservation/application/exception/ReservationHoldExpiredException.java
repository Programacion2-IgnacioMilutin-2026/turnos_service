package turnos.reservation.application.exception;

public class ReservationHoldExpiredException extends ReservationException {
    public ReservationHoldExpiredException(String holdId) {
        super("Hold expired: " + holdId);
    }
}
