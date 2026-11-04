package turnos.reservation.application.exception;

// La cátedra dice que no se puede cancelar, aunque aquí figura confirmada
public class ReservationNotCancellableException extends ReservationException {
    public ReservationNotCancellableException(String reservationProcessId) {
        super("Catedra does not allow cancelling reservation process " + reservationProcessId);
    }
}
