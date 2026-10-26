package turnos.availability.application.exception;

public class AvailabilityProfessionalNotFoundException extends AvailabilityException {
    public AvailabilityProfessionalNotFoundException(Long professionalId) {
        super("Professional not found: " + professionalId);
    }
}
