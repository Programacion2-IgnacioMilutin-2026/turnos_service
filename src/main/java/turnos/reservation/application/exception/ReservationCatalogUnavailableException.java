package turnos.reservation.application.exception;

public class ReservationCatalogUnavailableException extends ReservationException {
    public ReservationCatalogUnavailableException(String reason) {
        super("Catalog unavailable: " + reason);
    }
}
