package turnos.reservation.application.exception;

// La cátedra dice que la reserva es de otra cuenta técnica: no debería ocurrir, porque los dos backends la comparten
public class ReservationAppointmentOwnerMismatchException extends ReservationException {
    public ReservationAppointmentOwnerMismatchException(String reservationProcessId) {
        super("Catedra says reservation process " + reservationProcessId + " belongs to another technical account");
    }
}
