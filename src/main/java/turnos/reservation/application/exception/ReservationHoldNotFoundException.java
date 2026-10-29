package turnos.reservation.application.exception;

public class ReservationHoldNotFoundException extends ReservationException {
    public ReservationHoldNotFoundException(String holdId) {
        super("Catedra does not know hold " + holdId);
    }
}
