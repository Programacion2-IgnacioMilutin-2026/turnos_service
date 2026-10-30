package turnos.reservation.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

// Clave primaria asignada por la cátedra (el eventId), sin un Long autogenerado: excepción a la skill autorizada por
// el usuario. Persistable le indica a Spring Data que es nueva, para que guardarla no haga un SELECT antes del INSERT
@Getter
@Setter
@Entity
@Table(name = "processed_events")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEventEntity implements Persistable<String> {
    @Id
    private String eventId;
    private Instant processedAt;

    @Transient
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean newEntity = true;

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    // Una entidad leída o recién guardada ya no es nueva
    @PostLoad
    @PostPersist
    void markAsLoaded() {
        newEntity = false;
    }
}
