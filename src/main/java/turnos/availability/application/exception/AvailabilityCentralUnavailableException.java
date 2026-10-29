package turnos.availability.application.exception;

public class AvailabilityCentralUnavailableException extends AvailabilityException {
    public AvailabilityCentralUnavailableException(String reason) {
        super("Catedra unavailable: " + reason);
    }
}
