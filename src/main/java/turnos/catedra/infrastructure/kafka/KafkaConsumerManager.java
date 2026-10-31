package turnos.catedra.infrastructure.kafka;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.stereotype.Component;
import turnos.catedra.application.exception.CatedraEventNotSavedException;
import turnos.catedra.domain.model.TechnicalIntegration;
import turnos.catedra.domain.ports.out.TechnicalIntegrationListener;
import turnos.catedra.infrastructure.client.CatedraProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

// Paquete infrastructure/kafka: excepción a la skill autorizada por el usuario. Es el único lugar del servicio
// que conoce Kafka.
// Arma el consumo del topic de acciones cuando un login deja la integración habilitada, porque recién ahí se conocen
// los servidores, el topic y el consumer group. Conserva los datos del primer login hasta que el servicio se reinicia
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaConsumerManager implements TechnicalIntegrationListener {

    private final CatedraKafkaProperties catedraKafkaProperties;
    private final CatedraProperties catedraProperties;
    // Se pide recién al armar el consumo, después de un login. Si se recibiera al crear el bean, Spring no podría armar
    // el servicio: el login avisa a este manager, el oyente llega hasta reservation, y reservation usa el login
    private final ObjectProvider<AppointmentActionListener> appointmentActionListener;

    private TechnicalIntegration connectedWith;
    private KafkaMessageListenerContainer<String, String> container;

    @Override
    public synchronized void integrationEnabled(TechnicalIntegration integration) {
        if (!catedraKafkaProperties.enabled()) {
            log.debug("Kafka consumption is disabled");
            return;
        }
        if (connectedWith != null) {
            if (!sameKafka(connectedWith, integration)) {
                log.warn("A later technical login reported other Kafka settings; consumption keeps the first ones until the service restarts");
            }
            return;
        }
        connectedWith = integration;
        container = createContainer(integration);
        // Aparte del hilo del login: arrancar el consumo nunca lo demora
        KafkaMessageListenerContainer<String, String> starting = container;
        Thread.ofVirtual().name("catedra-kafka-start").start(() -> start(starting, integration));
    }

    // Si no pudo arrancar, un login posterior lo vuelve a intentar
    private synchronized void start(KafkaMessageListenerContainer<String, String> starting, TechnicalIntegration integration) {
        if (container != starting) {
            return;
        }
        try {
            starting.start();
            log.info("Consuming Catedra topic {} with consumer group {}", integration.kafkaAppointmentActionsTopic(),
                    consumerGroup(integration));
        } catch (RuntimeException e) {
            log.error("Kafka consumption could not start: {}", e.getClass().getSimpleName());
            connectedWith = null;
            container = null;
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (container != null) {
            container.stop();
        }
    }

    // Si el consumo está corriendo; sirve para comprobarlo
    synchronized boolean consuming() {
        return container != null && container.isRunning();
    }

    private KafkaMessageListenerContainer<String, String> createContainer(TechnicalIntegration integration) {
        ContainerProperties containerProperties = new ContainerProperties(integration.kafkaAppointmentActionsTopic());
        // Cada mensaje se da por leído recién cuando terminó de procesarse sin error
        containerProperties.setAckMode(ContainerProperties.AckMode.RECORD);
        containerProperties.setMessageListener((MessageListener<String, String>) appointmentActionListener.getObject()::onMessage);
        KafkaMessageListenerContainer<String, String> created = new KafkaMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<>(consumerConfiguration(integration)), containerProperties);
        created.setCommonErrorHandler(errorHandler());
        // Es el nombre del hilo que consume, en el log
        created.setBeanName("catedra-actions");
        return created;
    }

    private Map<String, Object> consumerConfiguration(TechnicalIntegration integration) {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, integration.kafkaBootstrapServers());
        configuration.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup(integration));
        // Sin una posición guardada, desde el mensaje más antiguo: así no se pierde un pedido publicado antes
        configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configuration.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        String protocol = catedraKafkaProperties.securityProtocol();
        configuration.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol);
        // Si el servidor pide credenciales, son las de la cuenta técnica. El cliente de Kafka no las muestra en el log
        if (protocol.startsWith("SASL")) {
            String mechanism = catedraKafkaProperties.saslMechanism();
            configuration.put(SaslConfigs.SASL_MECHANISM, mechanism);
            configuration.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfiguration(mechanism));
        }
        return configuration;
    }

    private String jaasConfiguration(String mechanism) {
        String module = mechanism.equals("PLAIN")
                ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                : "org.apache.kafka.common.security.scram.ScramLoginModule";
        return module + " required username=\"" + escape(catedraProperties.username())
                + "\" password=\"" + escape(catedraProperties.password()) + "\";";
    }

    // Una falla de la base se reintenta sin fin, con la pausa entre rondas: el mensaje no se pierde y los siguientes
    // esperan. Cualquier otro error se reintenta pocas veces y después el mensaje se descarta, para no frenar a todos
    private DefaultErrorHandler errorHandler() {
        CatedraKafkaProperties.Retry retry = catedraKafkaProperties.retry();
        MessageRetryBackOff limited = new MessageRetryBackOff(retry.initialDelay(), retry.multiplier(), retry.maxAttempts(), null);
        MessageRetryBackOff untilSaved = new MessageRetryBackOff(retry.initialDelay(), retry.multiplier(), retry.maxAttempts(), retry.pause());
        DefaultErrorHandler handler = new DefaultErrorHandler((message, error) -> log.error(
                "Catedra message at offset {} discarded after {} attempts because of an unexpected error: {}",
                message.offset(), retry.maxAttempts(), describe(error)), limited);
        handler.setBackOffFunction((message, error) -> notSaved(error) ? untilSaved : limited);
        handler.setRetryListeners((message, error, attempt) -> {
            if (notSaved(error) && attempt >= retry.maxAttempts()) {
                log.error("Catedra message at offset {} could not be saved after {} attempts, trying again in {}: {}",
                        message.offset(), attempt, retry.pause(), describe(error));
            } else {
                log.warn("Catedra message at offset {} attempt {} failed: {}", message.offset(), attempt, describe(error));
            }
        });
        return handler;
    }

    private String consumerGroup(TechnicalIntegration integration) {
        return catedraKafkaProperties.consumerGroup() != null
                ? catedraKafkaProperties.consumerGroup()
                : integration.kafkaConsumerGroupId();
    }

    private static boolean sameKafka(TechnicalIntegration first, TechnicalIntegration later) {
        return Objects.equals(first.kafkaBootstrapServers(), later.kafkaBootstrapServers())
                && Objects.equals(first.kafkaAppointmentActionsTopic(), later.kafkaAppointmentActionsTopic())
                && Objects.equals(first.kafkaConsumerGroupId(), later.kafkaConsumerGroupId());
    }

    private static boolean notSaved(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CatedraEventNotSavedException) {
                return true;
            }
        }
        return false;
    }

    // La causa original, sin el envoltorio del contenedor de Kafka
    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
