package turnos.catedra.infrastructure.kafka;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import turnos.catedra.domain.model.AdditionalInformationSubmission;
import turnos.catedra.domain.model.AdditionalInformationSubmissionResult;
import turnos.catedra.domain.model.TechnicalIntegration;
import turnos.catedra.domain.ports.out.AdditionalInformationPublisher;
import turnos.catedra.infrastructure.client.CatedraProperties;
import turnos.catedra.infrastructure.kafka.dto.AdditionalInformationSubmittedMessage;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Paquete infrastructure/kafka: excepción a la skill autorizada por el usuario.
// Publica en el topic de teléfono mientras un usuario espera la respuesta, así que todo tiene un tope de tiempo. El
// productor se crea en el primer envío, porque la dirección de Kafka llega con el login, y la conserva hasta que el
// servicio se reinicia, como el consumo. El topic y el groupId son los de la integración vigente en cada envío
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaAdditionalInformationPublisher implements AdditionalInformationPublisher {

    private static final String EVENT_TYPE = "AdditionalInformationSubmitted";
    private static final int SCHEMA_VERSION = 1;

    private final CatedraKafkaProperties catedraKafkaProperties;
    private final CatedraProperties catedraProperties;
    private final JsonMapper jsonMapper;

    private Producer<String, String> producer;

    @Override
    public AdditionalInformationSubmissionResult publish(AdditionalInformationSubmission submission, TechnicalIntegration integration) {
        String description = "event " + submission.getEventId() + " of reservation process " + submission.getReservationProcessId();
        try {
            ProducerRecord<String, String> message = new ProducerRecord<>(integration.kafkaAppointmentPhoneTopic(),
                    submission.getReservationProcessId(), jsonMapper.writeValueAsString(toMessage(submission, integration)));
            return send(message, integration.kafkaBootstrapServers(), description);
        } catch (RuntimeException e) {
            // Solo el tipo: el mensaje de un error no previsto podría incluir el contenido, que lleva el teléfono
            log.error("Unexpected error while publishing {}: {}", description, e.getClass().getSimpleName());
            return new AdditionalInformationSubmissionResult.NotPublished("Unexpected error: " + e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (producer != null) {
            producer.close(Duration.ofSeconds(1));
        }
    }

    // Hasta el máximo de intentos, sin pasarse del tope: un intento que ya no entraría no empieza. El mensaje es siempre
    // el mismo. Lo que repetir no arregla, como un rechazo de credenciales, corta enseguida
    private AdditionalInformationSubmissionResult send(ProducerRecord<String, String> message, String servers, String description) {
        CatedraKafkaProperties.Publish publish = catedraKafkaProperties.publish();
        long deadline = System.nanoTime() + publish.timeout().toNanos();
        String reason = "Not attempted";
        for (int attempt = 1; attempt <= publish.maxAttempts(); attempt++) {
            Duration delay = publish.delayBeforeAttempt(attempt);
            if (System.nanoTime() + delay.plus(publish.attemptTimeout()).toNanos() > deadline) {
                log.warn("Publishing {} stopped after {} attempts: another one would exceed {}", description, attempt - 1, publish.timeout());
                break;
            }
            if (!waitBefore(delay)) {
                return new AdditionalInformationSubmissionResult.NotPublished("Interrupted");
            }
            try {
                sendOnce(message, servers, publish.attemptTimeout());
                return new AdditionalInformationSubmissionResult.Published();
            } catch (RetriableException | TimeoutException e) {
                reason = describe(e);
                log.warn("Publishing {} attempt {}/{} failed: {}", description, attempt, publish.maxAttempts(), reason);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new AdditionalInformationSubmissionResult.NotPublished("Interrupted");
            } catch (ExecutionException e) {
                reason = describe(e.getCause());
                if (!(e.getCause() instanceof RetriableException)) {
                    log.error("Publishing {} failed and will not be retried: {}", description, reason);
                    return new AdditionalInformationSubmissionResult.NotPublished(reason);
                }
                log.warn("Publishing {} attempt {}/{} failed: {}", description, attempt, publish.maxAttempts(), reason);
            }
        }
        log.error("Publishing {} failed after the retries: {}", description, reason);
        return new AdditionalInformationSubmissionResult.NotPublished(reason);
    }

    // Lo que tarde el envío en pedir los metadatos sale del mismo tiempo del intento
    private void sendOnce(ProducerRecord<String, String> message, String servers, Duration attemptTimeout)
            throws ExecutionException, InterruptedException, TimeoutException {
        long started = System.nanoTime();
        Future<?> sent = producer(servers).send(message);
        long remaining = attemptTimeout.toNanos() - (System.nanoTime() - started);
        sent.get(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
    }

    private synchronized Producer<String, String> producer(String servers) {
        if (producer == null) {
            producer = createProducer(servers);
        }
        return producer;
    }

    // Protegido para que un test use un productor simulado
    protected Producer<String, String> createProducer(String servers) {
        return new KafkaProducer<>(producerConfiguration(servers));
    }

    // Por defecto, el cliente puede bloquear hasta un minuto: cada espera se limita al tiempo de un intento
    Map<String, Object> producerConfiguration(String servers) {
        int attemptTimeout = Math.toIntExact(catedraKafkaProperties.publish().attemptTimeout().toMillis());
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.ACKS_CONFIG, "all");
        configuration.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        configuration.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, attemptTimeout);
        configuration.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, attemptTimeout);
        configuration.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, attemptTimeout);
        configuration.putAll(catedraKafkaProperties.securityConfiguration(catedraProperties.username(), catedraProperties.password()));
        return configuration;
    }

    private static AdditionalInformationSubmittedMessage toMessage(AdditionalInformationSubmission submission, TechnicalIntegration integration) {
        return AdditionalInformationSubmittedMessage.builder()
                .eventId(submission.getEventId())
                .eventType(EVENT_TYPE)
                .groupId(integration.groupId())
                .reservationProcessId(submission.getReservationProcessId())
                .holdId(submission.getHoldId())
                .requestEventId(submission.getRequestEventId())
                .information(AdditionalInformationSubmittedMessage.Information.builder().phoneNumber(submission.getPhoneNumber()).build())
                .occurredAt(submission.getOccurredAt().toString())
                .schemaVersion(SCHEMA_VERSION)
                .build();
    }

    private static boolean waitBefore(Duration delay) {
        if (delay.isZero()) {
            return true;
        }
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
}
