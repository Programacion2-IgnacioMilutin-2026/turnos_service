package turnos.reservation.domain.model;

import lombok.*;

// El paciente que se informa a la cátedra al confirmar: el nombre y apellido actuales del usuario
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationPatient {
    private String firstName;
    private String lastName;
}
