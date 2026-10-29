package turnos.availability.application.exception;

public class AvailabilityProfessionalDisabledException extends AvailabilityException {
    public AvailabilityProfessionalDisabledException(Long professionalId) {
        super("Professional is disabled: " + professionalId);
    }
}
