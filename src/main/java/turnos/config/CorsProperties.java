package turnos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

// Paquete config fuera de los slices: excepción a la skill autorizada por el usuario.
// Orígenes de navegador que pueden usar la API. Vacío por defecto: la app nativa no necesita CORS
@ConfigurationProperties("turnos.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {
}
