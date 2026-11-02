package turnos.catedra.infrastructure.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

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
        @DefaultValue Retry retry,
        @DefaultValue Publish publish) {

    // Una variable del .env que existe pero está vacía llega como texto vacío: conserva el valor por defecto
    public CatedraKafkaProperties {
        enabled = enabled == null || enabled;
        securityProtocol = blank(securityProtocol) ? "PLAINTEXT" : securityProtocol;
        saslMechanism = blank(saslMechanism) ? "PLAIN" : saslMechanism;
        consumerGroup = blank(consumerGroup) ? null : consumerGroup;
    }

    // Lo que el consumidor y el productor necesitan para conectarse. Si el servidor pide credenciales, son las de la
    // cuenta técnica. El cliente de Kafka no las muestra en el log
    public Map<String, Object> securityConfiguration(String username, String password) {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        if (securityProtocol.startsWith("SASL")) {
            configuration.put(SaslConfigs.SASL_MECHANISM, saslMechanism);
            configuration.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfiguration(username, password));
        }
        return configuration;
    }

    private String jaasConfiguration(String username, String password) {
        String module = saslMechanism.equals("PLAIN")
                ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                : "org.apache.kafka.common.security.scram.ScramLoginModule";
        return module + " required username=\"" + escape(username) + "\" password=\"" + escape(password) + "\";";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
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

    // Publicar un mensaje mientras un usuario espera la respuesta. timeout es el tope de todos los intentos juntos
    public record Publish(
            @DefaultValue("10s") Duration timeout,
            @DefaultValue("2500ms") Duration attemptTimeout,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("500ms") Duration initialDelay,
            @DefaultValue("2") double multiplier) {

        // El primer intento no espera; desde el segundo, la espera crece con el multiplicador
        public Duration delayBeforeAttempt(int attempt) {
            if (attempt <= 1) {
                return Duration.ZERO;
            }
            return Duration.ofMillis((long) (initialDelay.toMillis() * Math.pow(multiplier, attempt - 2.0)));
        }
    }
}
