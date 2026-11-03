package turnos.reservation.domain.model;

import java.util.List;

// Una página de reservas y el total de todas las que cumplen el pedido
public record ReservationProcessPage(List<ReservationProcess> items, long totalItems) {
}
