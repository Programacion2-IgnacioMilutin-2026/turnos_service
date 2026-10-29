package turnos.reservation.application.exception;

public class ReservationProfessionalDisabledException extends ReservationException {
    public ReservationProfessionalDisabledException(Long professionalId) {
        super("Professional is disabled: " + professionalId);
    }
}
