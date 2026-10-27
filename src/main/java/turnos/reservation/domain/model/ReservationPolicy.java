package turnos.reservation.domain.model;

import java.time.Duration;
import java.time.ZoneId;

// La zona horaria en la que atienden los profesionales y el margen que se espera después del vencimiento de un hold
public record ReservationPolicy(ZoneId zone, Duration safetyMargin) {
}
