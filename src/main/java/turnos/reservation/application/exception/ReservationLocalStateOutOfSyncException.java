package turnos.reservation.application.exception;

// La cátedra avanzó el proceso, pero turnos no pudo guardarlo: queda para reconciliar
public class ReservationLocalStateOutOfSyncException extends ReservationException {
    public ReservationLocalStateOutOfSyncException(String reservationProcessId, Throwable cause) {
        super("Catedra confirmed reservation process " + reservationProcessId + " but it could not be saved", cause);
    }
}
