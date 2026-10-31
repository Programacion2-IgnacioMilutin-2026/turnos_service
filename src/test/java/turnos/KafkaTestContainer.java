package turnos;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

// Un Kafka real en Docker, compartido por toda la suite: arranca la primera vez que un test lo pide y lo apaga
// Testcontainers al terminar. Cada test usa sus propios topics y consumer groups, así no se mezclan
public final class KafkaTestContainer {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    private KafkaTestContainer() {
    }

    public static synchronized String bootstrapServers() {
        if (!KAFKA.isRunning()) {
            KAFKA.start();
        }
        return KAFKA.getBootstrapServers();
    }

    // Congela el contenedor, como un Kafka que deja de responder, y lo vuelve a la vida
    public static void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    public static void unpause() {
        DockerClientFactory.instance().client().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }
}
