package turnos.catedra.infrastructure.kafka;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;

// Cuánto esperar antes de volver a entregar un mensaje que falló. maxAttempts cuenta las entregas, como los intentos
// de las operaciones con la cátedra: entre una y otra, espera creciente desde initialDelay.
// Con pausa, agotados los intentos se sigue esperando la pausa entre entregas, sin fin; sin pausa, se deja de reintentar
record MessageRetryBackOff(Duration initialDelay, double multiplier, int maxAttempts, Duration pause) implements BackOff {

    @Override
    public BackOffExecution start() {
        return new BackOffExecution() {
            private int failures;

            @Override
            public long nextBackOff() {
                failures++;
                if (failures < maxAttempts) {
                    return (long) (initialDelay.toMillis() * Math.pow(multiplier, failures - 1));
                }
                return pause == null ? STOP : pause.toMillis();
            }
        };
    }
}
