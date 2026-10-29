package turnos.availability.application.exception;

public class AvailabilityServiceDegradedException extends AvailabilityException {
    public AvailabilityServiceDegradedException(String reason) {
        super("Catedra integration degraded: " + reason);
    }
}
