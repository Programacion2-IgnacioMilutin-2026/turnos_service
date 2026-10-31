package turnos.catedra.infrastructure.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRetryBackOffTest {

    @Test
    void withoutPauseItWaitsMoreEachTimeAndStopsAfterTheAttempts() {
        BackOffExecution execution = new MessageRetryBackOff(Duration.ofMillis(500), 2, 3, null).start();

        assertThat(LongStream.generate(execution::nextBackOff).limit(3).toArray())
                .containsExactly(500, 1000, BackOffExecution.STOP);
    }

    @Test
    void withPauseItKeepsTryingAfterTheAttemptsWaitingThePause() {
        BackOffExecution execution = new MessageRetryBackOff(Duration.ofMillis(500), 2, 3, Duration.ofSeconds(30)).start();

        assertThat(LongStream.generate(execution::nextBackOff).limit(6).toArray())
                .containsExactly(500, 1000, 30000, 30000, 30000, 30000);
    }

    @Test
    void eachMessageStartsCountingAgain() {
        MessageRetryBackOff backOff = new MessageRetryBackOff(Duration.ofMillis(500), 2, 3, null);
        BackOffExecution first = backOff.start();
        first.nextBackOff();
        first.nextBackOff();

        assertThat(backOff.start().nextBackOff()).isEqualTo(500);
    }
}
