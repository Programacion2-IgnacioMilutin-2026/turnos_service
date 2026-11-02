package turnos.reservation.application.exception;

public class ReservationPhoneRequestNotReadyException extends ReservationException {
    public ReservationPhoneRequestNotReadyException(String reservationProcessId) {
        super("The phone request of reservation process " + reservationProcessId + " has not arrived yet");
    }
}
