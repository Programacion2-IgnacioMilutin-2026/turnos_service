package turnos.reservation.application.exception;

public class ReservationPatientMismatchException extends ReservationException {
    public ReservationPatientMismatchException(String holdId) {
        super("Patient does not match the one of hold " + holdId);
    }
}
