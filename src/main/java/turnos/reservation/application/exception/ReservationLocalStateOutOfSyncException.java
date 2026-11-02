package turnos.reservation.application.exception;

// El estado guardado del proceso no refleja lo que pasó con la cátedra, porque no se pudo guardar: queda para reconciliar
public class ReservationLocalStateOutOfSyncException extends ReservationException {
    public ReservationLocalStateOutOfSyncException(String reservationProcessId, Throwable cause) {
        super("The state of reservation process " + reservationProcessId + " could not be saved", cause);
    }
}
