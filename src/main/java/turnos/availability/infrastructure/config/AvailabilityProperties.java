package turnos.availability.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

// Paquete infrastructure/config: excepción a la skill autorizada por el usuario
@ConfigurationProperties("turnos.availability")
public record AvailabilityProperties(
        @DefaultValue("America/Argentina/Mendoza") ZoneId zoneId,
        @DefaultValue("60m") Duration occupancyCacheMaxAge) {
}
