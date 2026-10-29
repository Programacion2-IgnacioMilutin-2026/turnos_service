package turnos.user.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Solo el nombre y el apellido: lo único del usuario que sale del slice cuando otro lo necesita
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserName {
    private String firstName;
    private String lastName;
}
