package turnos.reservation.application.exception;

public class ReservationNotSavedException extends ReservationException {
    public ReservationNotSavedException(String reservationProcessId, Throwable cause) {
        super("Reservation process could not be saved: " + reservationProcessId, cause);
    }

    // Para una escritura de varias tablas a la vez, donde la falla puede no ser de un proceso en particular
    public ReservationNotSavedException(Throwable cause) {
        super("Reservation data could not be saved", cause);
    }
}
