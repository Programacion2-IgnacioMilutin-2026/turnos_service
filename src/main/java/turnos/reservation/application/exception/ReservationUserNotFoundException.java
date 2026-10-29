package turnos.reservation.application.exception;

public class ReservationUserNotFoundException extends ReservationException {
    public ReservationUserNotFoundException(Long userId) {
        super("User of the token does not exist: " + userId);
    }
}
