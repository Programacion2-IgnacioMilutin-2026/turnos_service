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
        assertThat(properties.publish().timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.publish().attemptTimeout()).isEqualTo(Duration.ofMillis(2500));
        assertThat(properties.publish().maxAttempts()).isEqualTo(3);
        assertThat(properties.publish().initialDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.publish().multiplier()).isEqualTo(2);
    }

    @Test
    void theDefaultAttemptsAndWaitsFitInTheTimeLimit() {
        CatedraKafkaProperties.Publish publish = bind(Map.of()).publish();
        Duration worstCase = publish.attemptTimeout().multipliedBy(publish.maxAttempts())
                .plus(publish.delayBeforeAttempt(2)).plus(publish.delayBeforeAttempt(3));

        assertThat(worstCase).isEqualTo(Duration.ofSeconds(9)).isLessThanOrEqualTo(publish.timeout());
    }

    @Test
    void theWaitBeforeEachPublishingAttemptGrows() {
        CatedraKafkaProperties.Publish publish = bind(Map.of()).publish();

        assertThat(publish.delayBeforeAttempt(1)).isZero();
        assertThat(publish.delayBeforeAttempt(2)).isEqualTo(Duration.ofMillis(500));
        assertThat(publish.delayBeforeAttempt(3)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void withoutCredentialsOnlyTheProtocolIsConfigured() {
        assertThat(bind(Map.of()).securityConfiguration("cuenta", "secreto"))
                .containsOnly(Map.entry("security.protocol", "PLAINTEXT"));
    }

    @Test
    void withPlainTheCredentialsOfTheTechnicalAccountAreUsedAndEscaped() {
        CatedraKafkaProperties properties = bind(Map.of("turnos.catedra.kafka.security-protocol", "SASL_PLAINTEXT"));

        assertThat(properties.securityConfiguration("cuenta", "se\"cre\\to"))
                .containsEntry("security.protocol", "SASL_PLAINTEXT")
                .containsEntry("sasl.mechanism", "PLAIN")
                .containsEntry("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required "
                        + "username=\"cuenta\" password=\"se\\\"cre\\\\to\";");
    }

    @Test
    void anotherMechanismUsesTheScramModule() {
        CatedraKafkaProperties properties = bind(Map.of("turnos.catedra.kafka.security-protocol", "SASL_SSL",
                "turnos.catedra.kafka.sasl-mechanism", "SCRAM-SHA-512"));

        assertThat(properties.securityConfiguration("cuenta", "secreto"))
                .containsEntry("sasl.mechanism", "SCRAM-SHA-512")
                .hasEntrySatisfying("sasl.jaas.config", jaas -> assertThat(jaas.toString())
                        .startsWith("org.apache.kafka.common.security.scram.ScramLoginModule required"));
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

    @Test
    void publishingCanBeConfigured() {
        CatedraKafkaProperties.Publish publish = bind(Map.of(
                "turnos.catedra.kafka.publish.timeout", "4s", "turnos.catedra.kafka.publish.attempt-timeout", "1s",
                "turnos.catedra.kafka.publish.max-attempts", "2", "turnos.catedra.kafka.publish.initial-delay", "20ms",
                "turnos.catedra.kafka.publish.multiplier", "3")).publish();
        assertThat(publish.timeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(publish.attemptTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(publish.maxAttempts()).isEqualTo(2);
        assertThat(publish.initialDelay()).isEqualTo(Duration.ofMillis(20));
        assertThat(publish.multiplier()).isEqualTo(3);
    }

    // Así queda si la variable del .env existe pero está vacía
    @Test
    void emptyValuesKeepTheDefaults() {
        CatedraKafkaProperties properties = bind(Map.of("turnos.catedra.kafka.enabled", "",
                "turnos.catedra.kafka.security-protocol", "", "turnos.catedra.kafka.sasl-mechanism", " ",
                "turnos.catedra.kafka.consumer-group", "", "turnos.catedra.kafka.retry.pause", "",
                "turnos.catedra.kafka.publish.timeout", ""));
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.securityProtocol()).isEqualTo("PLAINTEXT");
        assertThat(properties.saslMechanism()).isEqualTo("PLAIN");
        assertThat(properties.consumerGroup()).isNull();
        assertThat(properties.retry().pause()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.publish().timeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
