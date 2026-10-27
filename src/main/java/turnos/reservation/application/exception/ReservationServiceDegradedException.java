package turnos.reservation.application.exception;

public class ReservationServiceDegradedException extends ReservationException {
    public ReservationServiceDegradedException(String reason) {
        super("Catedra integration degraded: " + reason);
    }
}
