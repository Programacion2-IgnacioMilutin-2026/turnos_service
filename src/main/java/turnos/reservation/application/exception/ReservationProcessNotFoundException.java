package turnos.reservation.application.exception;

// El proceso no existe o es de otro usuario: los dos casos se responden igual
public class ReservationProcessNotFoundException extends ReservationException {
    public ReservationProcessNotFoundException(String reservationProcessId) {
        super("Reservation process not found: " + reservationProcessId);
    }
}
