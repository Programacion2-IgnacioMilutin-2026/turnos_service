package turnos.catedra.infrastructure.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CatedraKafkaPropertiesTest {

    // Sin el entorno de los tests, que apaga el consumo: así se ven los valores por defecto de verdad
    private static CatedraKafkaProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("turnos.catedra.kafka", CatedraKafkaProperties.class);
    }

    @Test
    void withoutConfigurationItConsumesWithoutCredentialsAndTheSuggestedConsumerGroup() {
        CatedraKafkaProperties properties = bind(Map.of());
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.securityProtocol()).isEqualTo("PLAINTEXT");
        assertThat(properties.saslMechanism()).isEqualTo("PLAIN");
        assertThat(properties.consumerGroup()).isNull();
        assertThat(properties.retry().initialDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.retry().multiplier()).isEqualTo(2);
        assertThat(properties.retry().maxAttempts()).isEqualTo(3);
        assertThat(properties.retry().pause()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void everythingCanBeConfigured() {
        CatedraKafkaProperties properties = bind(Map.of("turnos.catedra.kafka.enabled", "false",
                "turnos.catedra.kafka.security-protocol", "SASL_SSL", "turnos.catedra.kafka.sasl-mechanism", "SCRAM-SHA-512",
                "turnos.catedra.kafka.consumer-group", "alumnos-proyecto-de-prueba-e2e",
                "turnos.catedra.kafka.retry.initial-delay", "10ms", "turnos.catedra.kafka.retry.multiplier", "1.5",
                "turnos.catedra.kafka.retry.max-attempts", "5", "turnos.catedra.kafka.retry.pause", "2s"));
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.securityProtocol()).isEqualTo("SASL_SSL");
        assertThat(properties.saslMechanism()).isEqualTo("SCRAM-SHA-512");
        assertThat(properties.consumerGroup()).isEqualTo("alumnos-proyecto-de-prueba-e2e");
        assertThat(properties.retry().initialDelay()).isEqualTo(Duration.ofMillis(10));
        assertThat(properties.retry().multiplier()).isEqualTo(1.5);
        assertThat(properties.retry().maxAttempts()).isEqualTo(5);
        assertThat(properties.retry().pause()).isEqualTo(Duration.ofSeconds(2));
    }

    // Así queda si la variable del .env existe pero está vacía
    @Test
    void emptyValuesKeepTheDefaults() {
        CatedraKafkaProperties properties = bind(Map.of("turnos.catedra.kafka.enabled", "",
                "turnos.catedra.kafka.security-protocol", "", "turnos.catedra.kafka.sasl-mechanism", " ",
                "turnos.catedra.kafka.consumer-group", "", "turnos.catedra.kafka.retry.pause", ""));
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.securityProtocol()).isEqualTo("PLAINTEXT");
        assertThat(properties.saslMechanism()).isEqualTo("PLAIN");
        assertThat(properties.consumerGroup()).isNull();
        assertThat(properties.retry().pause()).isEqualTo(Duration.ofSeconds(30));
    }
}
