package turnos.reservation.application.exception;

public class ReservationNotSavedException extends ReservationException {
    public ReservationNotSavedException(String reservationProcessId, Throwable cause) {
        super("Reservation process could not be saved: " + reservationProcessId, cause);
    }
}
