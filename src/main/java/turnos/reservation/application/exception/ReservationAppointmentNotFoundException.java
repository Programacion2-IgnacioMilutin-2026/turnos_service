package turnos.reservation.application.exception;

// La cátedra no conoce la reserva, aunque aquí figura confirmada
public class ReservationAppointmentNotFoundException extends ReservationException {
    public ReservationAppointmentNotFoundException(String reservationProcessId) {
        super("Catedra does not know reservation process " + reservationProcessId);
    }
}
