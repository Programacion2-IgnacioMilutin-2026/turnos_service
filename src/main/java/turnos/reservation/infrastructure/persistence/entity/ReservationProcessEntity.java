package turnos.reservation.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;
import turnos.reservation.domain.model.ReservationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

// Clave primaria asignada por la cátedra, sin un Long autogenerado: excepción a la skill autorizada por el usuario.
// Persistable le indica a Spring Data si la entidad es nueva, para que guardarla no haga un SELECT antes del INSERT
@Getter
@Setter
@Entity
@Table(name = "reservation_processes")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationProcessEntity implements Persistable<String> {
    @Id
    private String reservationProcessId;
    private String holdId;
    private Long userId;
    private Long professionalId;
    private String professionalFirstName;
    private String professionalLastName;
    private String professionalCategoryName;
    @Column(name = "appointment_date")
    private LocalDate date;
    private LocalTime startTime;
    @Enumerated(EnumType.STRING)
    private ReservationStatus status;
    private Instant expiresAt;
    private boolean needsReconciliation;
    private String requestEventId;

    @Transient
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean newEntity = true;

    @Override
    public String getId() {
        return reservationProcessId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    // Para guardar los cambios de un proceso que ya está en la base
    public void markAsExisting() {
        newEntity = false;
    }

    // Una entidad leída o recién guardada ya no es nueva
    @PostLoad
    @PostPersist
    void markAsLoaded() {
        newEntity = false;
    }
}
