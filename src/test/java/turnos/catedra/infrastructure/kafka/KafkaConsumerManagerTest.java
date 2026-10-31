package turnos.catedra.infrastructure.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import turnos.KafkaTestContainer;
import turnos.catedra.application.exception.CatedraEventNotSavedException;
import turnos.catedra.application.service.AdditionalInformationRequestService;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalIntegration;
import turnos.catedra.domain.ports.in.ReceiveAdditionalInformationRequestUseCase;
import turnos.catedra.infrastructure.client.CatedraProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class KafkaConsumerManagerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CatedraProperties CATEDRA = new CatedraProperties(null, "cuenta-tecnica", "secreto-de-prueba",
            null, null, null, null);

    // Cada test con su propio topic y consumer group
    private final String suffix = UUID.randomUUID().toString();
    private final String topic = "alumnos.turnos.acciones.test-" + suffix;
    private final String suggestedGroup = "alumnos-test-" + suffix;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private KafkaConsumerManager manager;

    @AfterEach
    void stopConsuming() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void whenDisabledItNeverConsumes() throws Exception {
        manager = manager(properties(false, null, "PLAINTEXT"), recording());
        publish(request("request-event-1"));

        manager.integrationEnabled(integration(topic));

        Thread.sleep(2000);
        assertThat(manager.consuming()).isFalse();
        assertThat(received).isEmpty();
    }

    @Test
    void theFirstNoticeStartsConsumingFromTheOldestMessageWithTheSuggestedGroup() throws Exception {
        manager = manager(properties(true, null, "PLAINTEXT"), recording());
        publish(request("request-event-1"));

        manager.integrationEnabled(integration(topic));

        eventually(() -> received.size() == 1);
        assertThat(received).containsExactly("request-event-1");
        eventually(() -> committedOffset(suggestedGroup) == 1);
        assertThat(manager.consuming()).isTrue();
    }

    @Test
    void aConfiguredGroupReplacesTheSuggestedOne() throws Exception {
        String testGroup = suggestedGroup + "-e2e";
        manager = manager(properties(true, testGroup, "PLAINTEXT"), recording());
        publish(request("request-event-1"));

        manager.integrationEnabled(integration(topic));

        eventually(() -> committedOffset(testGroup) == 1);
        assertThat(committedOffset(suggestedGroup)).isEqualTo(-1);
    }

    @Test
    void aLaterNoticeWithOtherKafkaSettingsIsLoggedAndChangesNothing(CapturedOutput output) throws Exception {
        manager = manager(properties(true, null, "PLAINTEXT"), recording());
        manager.integrationEnabled(integration(topic));
        String otherTopic = topic + "-other";

        manager.integrationEnabled(integration(otherTopic));
        publish(otherTopic, request("request-event-other"));
        publish(request("request-event-1"));

        eventually(() -> received.contains("request-event-1"));
        Thread.sleep(1000);
        assertThat(received).containsExactly("request-event-1");
        assertThat(output.getAll()).contains("WARN")
                .contains("A later technical login reported other Kafka settings; consumption keeps the first ones until the service restarts");
    }

    @Test
    void aMessageThatCouldNotBeSavedIsRetriedUntilItIsSavedAndTheNextOneWaits(CapturedOutput output) throws Exception {
        AtomicInteger failuresLeft = new AtomicInteger(4);
        manager = manager(properties(true, null, "PLAINTEXT"), request -> {
            if (request.getEventId().equals("request-event-1") && failuresLeft.getAndDecrement() > 0) {
                throw new CatedraEventNotSavedException(request.getEventId(), "database down");
            }
            received.add(request.getEventId());
        });
        publish(request("request-event-1"));
        publish(request("request-event-2"));

        manager.integrationEnabled(integration(topic));

        eventually(() -> received.size() == 2);
        assertThat(received).containsExactly("request-event-1", "request-event-2");
        eventually(() -> committedOffset(suggestedGroup) == 2);
        assertThat(output.getAll())
                .contains("Catedra message at offset 0 attempt 1 failed: CatedraEventNotSavedException: "
                        + "Catedra event request-event-1 could not be saved: database down")
                .contains("Catedra message at offset 0 could not be saved after 2 attempts, trying again in PT0.2S");
    }

    @Test
    void anUnexpectedErrorIsRetriedAFewTimesAndThenTheMessageIsDiscarded(CapturedOutput output) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        manager = manager(properties(true, null, "PLAINTEXT"), request -> {
            if (request.getEventId().equals("request-event-1")) {
                attempts.incrementAndGet();
                throw new IllegalStateException("bug");
            }
            received.add(request.getEventId());
        });
        publish(request("request-event-1"));
        publish(request("request-event-2"));

        manager.integrationEnabled(integration(topic));

        eventually(() -> received.contains("request-event-2"));
        assertThat(attempts).hasValue(2);
        eventually(() -> committedOffset(suggestedGroup) == 2);
        assertThat(output.getAll()).contains("ERROR")
                .contains("Catedra message at offset 0 discarded after 2 attempts because of an unexpected error: IllegalStateException: bug");
    }

    @Test
    void theCredentialsOfTheTechnicalAccountNeverReachTheLog(CapturedOutput output) throws Exception {
        manager = manager(properties(true, null, "SASL_PLAINTEXT"), recording());

        manager.integrationEnabled(integration(topic));

        Thread.sleep(2000);
        assertThat(output.getAll()).contains("sasl.jaas.config").doesNotContain("secreto-de-prueba");
    }

    @Test
    void afterStoppingItNoLongerConsumes() throws Exception {
        manager = manager(properties(true, null, "PLAINTEXT"), recording());
        manager.integrationEnabled(integration(topic));
        eventually(manager::consuming);

        manager.stop();

        assertThat(manager.consuming()).isFalse();
    }

    private KafkaConsumerManager manager(CatedraKafkaProperties properties, ReceiveAdditionalInformationRequestUseCase useCase) {
        AppointmentActionListener listener = new AppointmentActionListener(new AppointmentActionReader(JSON),
                new AdditionalInformationRequestService(useCase));
        return new KafkaConsumerManager(properties, CATEDRA,
                new StaticListableBeanFactory(Map.of("appointmentActionListener", listener))
                        .getBeanProvider(AppointmentActionListener.class));
    }

    private ReceiveAdditionalInformationRequestUseCase recording() {
        return request -> received.add(request.getEventId());
    }

    // Esperas cortas para que el test sea rápido; las reglas son las de la configuración real
    private static CatedraKafkaProperties properties(boolean enabled, String consumerGroup, String protocol) {
        return new CatedraKafkaProperties(enabled, protocol, "PLAIN", consumerGroup,
                new CatedraKafkaProperties.Retry(Duration.ofMillis(10), 2, 2, Duration.ofMillis(200)));
    }

    private TechnicalIntegration integration(String actionsTopic) {
        return new TechnicalIntegration("test-" + suffix, KafkaTestContainer.bootstrapServers(), suggestedGroup,
                actionsTopic, "catedra.turnos.telefono.test-" + suffix, ProvisioningStatus.PROVISIONED);
    }

    private String request(String eventId) {
        ObjectNode request = (ObjectNode) JSON.readTree(read("additional-information-requested.json"));
        request.put("eventId", eventId);
        return request.toString();
    }

    private void publish(String value) throws Exception {
        publish(topic, value);
    }

    private static void publish(String topic, String value) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, "process-a92b47d0", value)).get(10, TimeUnit.SECONDS);
        }
    }

    // La posición confirmada del consumer group en el topic, o -1 si no confirmó ninguna
    private long committedOffset(String group) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers()))) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS).get(new TopicPartition(topic, 0));
            return offset == null ? -1 : offset.offset();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition not met in 30 s").isBefore(deadline);
            Thread.sleep(100);
        }
    }

    private static String read(String name) {
        try (InputStream input = KafkaConsumerManagerTest.class.getResourceAsStream("/catedra/kafka/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
