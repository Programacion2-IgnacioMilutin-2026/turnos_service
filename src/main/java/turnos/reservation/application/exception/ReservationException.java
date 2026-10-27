package turnos.reservation.application.exception;

public class ReservationException extends RuntimeException {
    protected ReservationException(String message) {
        super(message);
    }

    protected ReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
