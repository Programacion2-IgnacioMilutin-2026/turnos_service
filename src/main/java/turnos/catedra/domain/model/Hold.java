package turnos.catedra.domain.model;

import lombok.*;

import java.time.Instant;

// Un turno retenido por la cátedra hasta expiresAt; sus ids los asigna la cátedra
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Hold {
    private String holdId;
    private String reservationProcessId;
    private Instant expiresAt;
}
