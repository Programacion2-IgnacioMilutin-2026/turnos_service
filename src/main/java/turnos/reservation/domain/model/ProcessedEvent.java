package turnos.reservation.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// Un evento de la cátedra que ya se procesó. Se identifica por el eventId que asigna la cátedra, un texto, y no por
// un Long autogenerado: excepción a la skill autorizada por el usuario. Sirve para no repetir el efecto de un evento
// que llega más de una vez
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {
    private String eventId;
    private Instant processedAt;
}
