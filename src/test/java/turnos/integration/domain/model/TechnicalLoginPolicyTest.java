package turnos.integration.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalLoginPolicyTest {

    private final TechnicalLoginPolicy policy = new TechnicalLoginPolicy(
            5, Duration.ofSeconds(1), 2, Duration.ofSeconds(30), Duration.ofSeconds(60));

    @Test
    void theFirstAttemptDoesNotWaitAndTheNextOnesDoubleTheWait() {
        assertThat(IntStream.rangeClosed(1, 5).mapToObj(policy::delayBeforeAttempt))
                .containsExactly(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2),
                        Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @Test
    void theWaitNeverExceedsTheMaximum() {
        assertThat(policy.delayBeforeAttempt(10)).isEqualTo(Duration.ofSeconds(30));
    }
}
