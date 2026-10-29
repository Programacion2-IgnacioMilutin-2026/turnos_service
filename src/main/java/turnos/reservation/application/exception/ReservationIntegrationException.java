package turnos.reservation.application.exception;

public class ReservationIntegrationException extends ReservationException {
    public ReservationIntegrationException(String reason) {
        super("Internal integration error: " + reason);
    }
}
