package turnos.reservation.domain.ports.out;

import turnos.reservation.domain.model.ReservationPatient;

import java.util.Optional;

public interface UserDirectory {
    boolean exists(Long userId);

    // Vacío si el usuario no existe
    Optional<ReservationPatient> findPatient(Long userId);
}
