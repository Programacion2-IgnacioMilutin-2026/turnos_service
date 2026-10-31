package e2e;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.Optional;

// El consumo de Kafka en la suite E2E. Ningún E2E puede usar el consumer group del servicio: leería y daría por leídos
// mensajes del servicio real. Con TURNOS_E2E_KAFKA_CONSUMER_GROUP en el .env, el E2E consume con ese consumer group,
// que tiene que terminar en -e2e para que un error de tipeo no apunte al del servicio; sin ella, no consume.
// Solo lo activa el E2E que espera un pedido de la cátedra: si dos E2E consumieran con el mismo consumer group,
// Kafka podría entregarle el pedido al que no tiene el proceso
final class E2EKafka {

    private static final String VARIABLE = "TURNOS_E2E_KAFKA_CONSUMER_GROUP";

    private E2EKafka() {
    }

    static Optional<String> consumerGroup() {
        String value = E2EEnvFile.read().getProperty(VARIABLE, "").trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (!value.endsWith("-e2e")) {
            throw new IllegalStateException(VARIABLE + " must end with -e2e, so that it is never the consumer group of the service");
        }
        return Optional.of(value);
    }

    static void configure(DynamicPropertyRegistry registry) {
        consumerGroup().ifPresentOrElse(group -> {
            registry.add("turnos.catedra.kafka.enabled", () -> "true");
            registry.add("turnos.catedra.kafka.consumer-group", () -> group);
        }, () -> registry.add("turnos.catedra.kafka.enabled", () -> "false"));
    }
}
