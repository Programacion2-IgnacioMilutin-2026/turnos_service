package turnos.reservation.application.exception;

// El mensaje no incluye el número: es un dato personal
public class ReservationInvalidPhoneNumberException extends ReservationException {
    public ReservationInvalidPhoneNumberException() {
        super("The phone number does not have a valid format");
    }
}
