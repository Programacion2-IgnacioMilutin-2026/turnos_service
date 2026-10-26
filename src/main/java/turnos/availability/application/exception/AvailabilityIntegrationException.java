package turnos.availability.application.exception;

public class AvailabilityIntegrationException extends AvailabilityException {
    public AvailabilityIntegrationException(String reason) {
        super("Internal integration error: " + reason);
    }
}
