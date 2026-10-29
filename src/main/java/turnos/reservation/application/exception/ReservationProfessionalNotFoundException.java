package turnos.reservation.application.exception;

public class ReservationProfessionalNotFoundException extends ReservationException {
    public ReservationProfessionalNotFoundException(Long professionalId) {
        super("Professional not found: " + professionalId);
    }
}
