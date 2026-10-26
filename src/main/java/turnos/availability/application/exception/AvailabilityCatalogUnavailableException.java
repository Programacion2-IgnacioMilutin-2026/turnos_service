package turnos.availability.application.exception;

public class AvailabilityCatalogUnavailableException extends AvailabilityException {
    public AvailabilityCatalogUnavailableException(String reason) {
        super("Catalog unavailable: " + reason);
    }
}
