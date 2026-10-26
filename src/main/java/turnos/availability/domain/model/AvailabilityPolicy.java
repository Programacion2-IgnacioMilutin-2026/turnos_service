package turnos.availability.domain.model;

import java.time.Duration;
import java.time.ZoneId;

// La zona horaria en la que atienden los profesionales y cuánto puede tener una entrada del caché
public record AvailabilityPolicy(ZoneId zone, Duration maxCacheAge) {
}
