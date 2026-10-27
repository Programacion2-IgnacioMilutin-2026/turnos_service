package turnos.reservation.application.exception;

public class ReservationCentralUnavailableException extends ReservationException {
    public ReservationCentralUnavailableException(String reason) {
        super("Catedra unavailable: " + reason);
    }
}
