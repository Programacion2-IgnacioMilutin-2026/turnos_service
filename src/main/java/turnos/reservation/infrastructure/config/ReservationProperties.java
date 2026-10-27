package turnos.reservation.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

// Paquete infrastructure/config: excepción a la skill autorizada por el usuario
@ConfigurationProperties("turnos.reservation")
public record ReservationProperties(
        @DefaultValue("America/Argentina/Mendoza") ZoneId zoneId,
        @DefaultValue("30s") Duration safetyMargin) {
}
