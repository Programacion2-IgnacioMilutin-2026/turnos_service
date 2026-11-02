package turnos.catedra.infrastructure.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;
import turnos.KafkaTestTopic;
import turnos.KafkaTestContainer;
import turnos.catedra.domain.model.AdditionalInformationSubmission;
import turnos.catedra.domain.model.AdditionalInformationSubmissionResult;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalIntegration;
import turnos.catedra.infrastructure.client.CatedraProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class KafkaAdditionalInformationPublisherTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CatedraProperties CATEDRA = new CatedraProperties(null, "cuenta-tecnica", "secreto-de-prueba",
            null, null, null, null);
    private static final String PHONE = "+5492615551234";
    private static final AdditionalInformationSubmission SUBMISSION = AdditionalInformationSubmission.builder()
            .eventId("phone-submitted-b91e")
            .reservationProcessId("process-a92b47d0")
            .holdId("hold-6c0c2f41")
            .requestEventId("request-event-8a31")
            .phoneNumber(PHONE)
            .occurredAt(Instant.parse("2026-10-12T14:36:00Z"))
            .build();
    private static final TechnicalIntegration INTEGRATION = integration("kafka.catedra.internal:9092", "catedra.turnos.telefono.proyecto-juan-perez");
    private static final Duration SHORT_WAIT = Duration.ofMillis(50);

    private final ScriptedProducer producer = new ScriptedProducer();
    private final List<String> serversOfEachProducer = new ArrayList<>();
    private KafkaAdditionalInformationPublisher publisher;

    @AfterEach
    void stopPublisher() {
        if (publisher != null) {
            publisher.stop();
        }
    }

    @Test
    void theMessageHasEveryFieldTheCatedraExpectsAndItsKeyIsTheProcess() {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));

        assertThat(publisher.publish(SUBMISSION, INTEGRATION)).isInstanceOf(AdditionalInformationSubmissionResult.Published.class);

        assertThat(producer.sent).singleElement().satisfies(message -> {
            assertThat(message.topic()).isEqualTo("catedra.turnos.telefono.proyecto-juan-perez");
            assertThat(message.key()).isEqualTo("process-a92b47d0");
            assertThat(JSON.readTree(message.value())).isEqualTo(JSON.readTree(read("additional-information-submitted.json")));
        });
    }

    @Test
    void aRetriableFailureIsRetriedWithTheSameMessageAndNothingElseIsNeededAfterItWorks() {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));
        producer.outcomes.add(CompletableFuture.failedFuture(new NetworkException("connection lost")));
        producer.outcomes.add(CompletableFuture.failedFuture(new NetworkException("connection lost")));

        assertThat(publisher.publish(SUBMISSION, INTEGRATION)).isInstanceOf(AdditionalInformationSubmissionResult.Published.class);

        assertThat(producer.sent).hasSize(3);
        assertThat(producer.sent).extracting(ProducerRecord::value).containsOnly(producer.sent.getFirst().value());
        assertThat(producer.sent).extracting(ProducerRecord::key).containsOnly("process-a92b47d0");
    }

    @Test
    void afterTheMaximumOfAttemptsItIsNotPublishedAndEachAttemptIsInTheLog(CapturedOutput output) {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));
        for (int i = 0; i < 5; i++) {
            producer.outcomes.add(CompletableFuture.failedFuture(new NetworkException("connection lost")));
        }

        assertThat(publisher.publish(SUBMISSION, INTEGRATION)).isInstanceOf(AdditionalInformationSubmissionResult.NotPublished.class);

        assertThat(producer.sent).hasSize(3);
        assertThat(output.getAll())
                .contains("event phone-submitted-b91e of reservation process process-a92b47d0 attempt 1/3 failed")
                .contains("attempt 2/3 failed").contains("attempt 3/3 failed")
                .contains("failed after the retries: NetworkException: connection lost");
    }

    @Test
    void theWaitBetweenAttemptsGrows() {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));
        for (int i = 0; i < 3; i++) {
            producer.outcomes.add(CompletableFuture.failedFuture(new NetworkException("connection lost")));
        }

        publisher.publish(SUBMISSION, INTEGRATION);

        assertThat(producer.sentAtNanos).hasSize(3);
        assertThat(Duration.ofNanos(producer.sentAtNanos.get(1) - producer.sentAtNanos.get(0))).isGreaterThanOrEqualTo(SHORT_WAIT);
        assertThat(Duration.ofNanos(producer.sentAtNanos.get(2) - producer.sentAtNanos.get(1))).isGreaterThanOrEqualTo(SHORT_WAIT.multipliedBy(2));
    }

    @Test
    void rejectedCredentialsAreNotRetriedAndTheReasonIsInTheLog(CapturedOutput output) {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));
        producer.outcomes.add(CompletableFuture.failedFuture(new AuthenticationException("bad credentials")));

        AdditionalInformationSubmissionResult result = publisher.publish(SUBMISSION, INTEGRATION);

        assertThat(result).isEqualTo(new AdditionalInformationSubmissionResult.NotPublished("AuthenticationException: bad credentials"));
        assertThat(producer.sent).hasSize(1);
        assertThat(output.getAll()).contains("will not be retried: AuthenticationException: bad credentials");
    }

    @Test
    void anUnauthorizedTopicIsNotRetried() {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));
        producer.outcomes.add(CompletableFuture.failedFuture(new TopicAuthorizationException("not allowed")));

        assertThat(publisher.publish(SUBMISSION, INTEGRATION)).isInstanceOf(AdditionalInformationSubmissionResult.NotPublished.class);

        assertThat(producer.sent).hasSize(1);
    }

    @Test
    void anAttemptWithoutAnswerCountsAsFailedAndIsRetried() {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofMillis(100), 2));
        producer.outcomes.add(new CompletableFuture<>());

        assertThat(publisher.publish(SUBMISSION, INTEGRATION)).isInstanceOf(AdditionalInformationSubmissionResult.Published.class);

        assertThat(producer.sent).hasSize(2);
    }

    @Test
    void anAttemptThatWouldExceedTheTimeLimitDoesNotStart(CapturedOutput output) {
        publisher = scripted(publish(Duration.ofMillis(300), Duration.ofMillis(200), 5));
        producer.outcomes.add(new CompletableFuture<>());
        long started = System.nanoTime();

        assertThat(publisher.publish(SUBMISSION, INTEGRATION)).isInstanceOf(AdditionalInformationSubmissionResult.NotPublished.class);

        assertThat(producer.sent).hasSize(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(300));
        assertThat(output.getAll()).contains("stopped after 1 attempts");
    }

    @Test
    void anUnexpectedErrorIsNotRetriedAndItsMessageDoesNotReachTheLog(CapturedOutput output) {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));
        producer.failure = new IllegalStateException("message with " + PHONE);

        AdditionalInformationSubmissionResult result = publisher.publish(SUBMISSION, INTEGRATION);

        assertThat(result).isEqualTo(new AdditionalInformationSubmissionResult.NotPublished("Unexpected error: IllegalStateException"));
        assertThat(output.getAll()).contains("Unexpected error while publishing").doesNotContain(PHONE);
    }

    @Test
    void anInterruptionStopsTheAttemptsAndKeepsTheThreadInterrupted() {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));
        producer.outcomes.add(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThat(publisher.publish(SUBMISSION, INTEGRATION))
                    .isEqualTo(new AdditionalInformationSubmissionResult.NotPublished("Interrupted"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void thePhoneNeverReachesTheLog(CapturedOutput output) {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));
        producer.outcomes.add(CompletableFuture.failedFuture(new NetworkException("connection lost")));
        producer.outcomes.add(CompletableFuture.failedFuture(new AuthenticationException("bad credentials")));

        publisher.publish(SUBMISSION, INTEGRATION);

        assertThat(output.getAll()).doesNotContain(PHONE);
    }

    @Test
    void theProducerIsCreatedOnceWithTheFirstServersAndTheOtherDataComeFromTheCurrentIntegration() {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));

        publisher.publish(SUBMISSION, integration("kafka-1:9092", "catedra.turnos.telefono.uno"));
        publisher.publish(SUBMISSION, integration("kafka-2:9092", "catedra.turnos.telefono.dos"));

        assertThat(serversOfEachProducer).containsExactly("kafka-1:9092");
        assertThat(producer.sent).extracting(ProducerRecord::topic)
                .containsExactly("catedra.turnos.telefono.uno", "catedra.turnos.telefono.dos");
    }

    @Test
    void stoppingClosesTheProducer() {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));
        publisher.publish(SUBMISSION, INTEGRATION);

        publisher.stop();

        assertThat(producer.closed()).isTrue();
    }

    @Test
    void stoppingWithoutHavingPublishedDoesNothing() {
        publisher = scripted(publish(Duration.ofSeconds(5), Duration.ofSeconds(1), 3));

        publisher.stop();

        assertThat(producer.closed()).isFalse();
    }

    @Test
    void everyWaitOfTheClientIsLimitedToAnAttemptAndTheCredentialsAreTheOnesOfTheTechnicalAccount() {
        publisher = new KafkaAdditionalInformationPublisher(properties("SASL_PLAINTEXT", publish(Duration.ofSeconds(5), Duration.ofMillis(2500), 3)), CATEDRA, JSON);

        Map<String, Object> configuration = publisher.producerConfiguration("kafka.catedra.internal:9092");

        assertThat(configuration).containsEntry("bootstrap.servers", "kafka.catedra.internal:9092")
                .containsEntry("acks", "all").containsEntry("linger.ms", 0)
                .containsEntry("max.block.ms", 2500).containsEntry("request.timeout.ms", 2500)
                .containsEntry("delivery.timeout.ms", 2500).containsEntry("security.protocol", "SASL_PLAINTEXT");
        assertThat(configuration.get("sasl.jaas.config").toString()).contains("cuenta-tecnica", "secreto-de-prueba");
    }

    @Test
    void aRealKafkaReceivesTheMessageWithItsKey() throws Exception {
        KafkaTestTopic topic = KafkaTestTopic.random("phone");
        publisher = new KafkaAdditionalInformationPublisher(properties("PLAINTEXT", publish(Duration.ofSeconds(30), Duration.ofSeconds(10), 3)), CATEDRA, JSON);

        AdditionalInformationSubmissionResult result = publisher.publish(SUBMISSION, integration(KafkaTestContainer.bootstrapServers(), topic.topic()));

        assertThat(result).isInstanceOf(AdditionalInformationSubmissionResult.Published.class);
        assertThat(topic.messages()).singleElement().satisfies((ConsumerRecord<String, String> message) -> {
            assertThat(message.key()).isEqualTo("process-a92b47d0");
            assertThat(JSON.readTree(message.value())).isEqualTo(JSON.readTree(read("additional-information-submitted.json")));
        });
    }

    @Test
    void anUnreachableKafkaIsNotPublishedAndAnswersWithinTheTimeLimit(CapturedOutput output) {
        publisher = new KafkaAdditionalInformationPublisher(properties("PLAINTEXT", publish(Duration.ofSeconds(3), Duration.ofMillis(500), 3)), CATEDRA, JSON);
        long started = System.nanoTime();

        AdditionalInformationSubmissionResult result = publisher.publish(SUBMISSION, integration("localhost:1", "catedra.turnos.telefono.nadie"));

        assertThat(result).isInstanceOf(AdditionalInformationSubmissionResult.NotPublished.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThanOrEqualTo(Duration.ofSeconds(3));
        assertThat(output.getAll()).contains("attempt 3/3 failed").doesNotContain(PHONE);
    }

    private KafkaAdditionalInformationPublisher scripted(CatedraKafkaProperties.Publish publish) {
        return new KafkaAdditionalInformationPublisher(properties("PLAINTEXT", publish), CATEDRA, JSON) {
            @Override
            protected Producer<String, String> createProducer(String servers) {
                serversOfEachProducer.add(servers);
                return producer;
            }
        };
    }

    private static CatedraKafkaProperties.Publish publish(Duration timeout, Duration attemptTimeout, int maxAttempts) {
        return new CatedraKafkaProperties.Publish(timeout, attemptTimeout, maxAttempts, SHORT_WAIT, 2);
    }

    private static CatedraKafkaProperties properties(String protocol, CatedraKafkaProperties.Publish publish) {
        return new CatedraKafkaProperties(true, protocol, "PLAIN", null, null, publish);
    }

    private static TechnicalIntegration integration(String servers, String phoneTopic) {
        return new TechnicalIntegration("proyecto-juan-perez", servers, "alumnos-proyecto-juan-perez",
                "alumnos.turnos.acciones.proyecto-juan-perez", phoneTopic, ProvisioningStatus.PROVISIONED);
    }

    private static String read(String name) {
        try (InputStream input = KafkaAdditionalInformationPublisherTest.class.getResourceAsStream("/catedra/kafka/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Responde a cada envío con el resultado que le indique el test; sin ninguno, confirma
    private static final class ScriptedProducer extends MockProducer<String, String> {
        private final Deque<CompletableFuture<RecordMetadata>> outcomes = new ArrayDeque<>();
        private final List<ProducerRecord<String, String>> sent = new ArrayList<>();
        private final List<Long> sentAtNanos = new ArrayList<>();
        private RuntimeException failure;

        private ScriptedProducer() {
            super(true, null, new StringSerializer(), new StringSerializer());
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<String, String> message) {
            if (failure != null) {
                throw failure;
            }
            sent.add(message);
            sentAtNanos.add(System.nanoTime());
            CompletableFuture<RecordMetadata> outcome = outcomes.poll();
            return outcome != null ? outcome : CompletableFuture.completedFuture(null);
        }
    }
}
