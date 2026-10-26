package turnos.catalog.infrastructure.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Valores cortos: el usuario espera la respuesta mientras se consulta catálogo
@ConfigurationProperties("turnos.catalog")
public record CatalogProperties(
        String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("500ms") Duration initialDelay,
        @DefaultValue("2") double multiplier) {
}
