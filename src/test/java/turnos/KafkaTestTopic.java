package turnos;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Un topic de acciones propio de un test, en el Kafka de Testcontainers, con el consumer group que lo lee:
// publica mensajes como lo haría la cátedra y dice hasta dónde confirmó el servicio
public record KafkaTestTopic(String topic, String consumerGroup) {

    public static KafkaTestTopic random(String name) {
        String suffix = name + "-" + UUID.randomUUID();
        return new KafkaTestTopic("alumnos.turnos.acciones." + suffix, "alumnos-" + suffix);
    }

    public void publish(String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // La posición que confirmó el consumer group, o -1 si no confirmó ninguna
    public long committedOffset() {
        try (Admin admin = admin()) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(consumerGroup).partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS).get(partition());
            return offset == null ? -1 : offset.offset();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // Cuántos mensajes se publicaron en el topic
    public long endOffset() {
        try (Admin admin = admin()) {
            return admin.listOffsets(Map.of(partition(), OffsetSpec.latest())).partitionResult(partition())
                    .get(10, TimeUnit.SECONDS).offset();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // El servicio leyó y confirmó todo lo publicado
    public boolean allConsumed() {
        long end = endOffset();
        return end > 0 && committedOffset() == end;
    }

    private TopicPartition partition() {
        return new TopicPartition(topic, 0);
    }

    private static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers()));
    }
}
