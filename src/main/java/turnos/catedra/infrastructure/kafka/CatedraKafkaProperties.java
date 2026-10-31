package turnos.catedra.infrastructure.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Cómo se conecta el servicio al Kafka de la cátedra. Los servidores, los topics y el consumer group sugerido llegan
// con el login técnico; acá solo va lo que el login no informa. Las credenciales, si el servidor las pide, son las de
// la cuenta técnica (CatedraProperties), así que este record no guarda ninguna.
// consumerGroup reemplaza al sugerido: vacío en la aplicación, solo lo completan las pruebas contra la cátedra real
@ConfigurationProperties("turnos.catedra.kafka")
public record CatedraKafkaProperties(
        @DefaultValue("true") Boolean enabled,
        @DefaultValue("PLAINTEXT") String securityProtocol,
        @DefaultValue("PLAIN") String saslMechanism,
        String consumerGroup,
        @DefaultValue Retry retry) {

    // Una variable del .env que existe pero está vacía llega como texto vacío: conserva el valor por defecto
    public CatedraKafkaProperties {
        enabled = enabled == null || enabled;
        securityProtocol = blank(securityProtocol) ? "PLAINTEXT" : securityProtocol;
        saslMechanism = blank(saslMechanism) ? "PLAIN" : saslMechanism;
        consumerGroup = blank(consumerGroup) ? null : consumerGroup;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // Reintentos al procesar un mensaje. Si la base sigue fallando, después de la pausa se vuelve a empezar con el
    // mismo mensaje; otro error se descarta después de los intentos
    public record Retry(
            @DefaultValue("500ms") Duration initialDelay,
            @DefaultValue("2") double multiplier,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("30s") Duration pause) {
    }
}
