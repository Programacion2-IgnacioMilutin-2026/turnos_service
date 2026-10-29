package turnos.reservation.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.reservation.domain.model.ReservationPatient;
import turnos.reservation.domain.ports.out.UserDirectory;
import turnos.user.domain.ports.in.ExistsUserByIdUseCase;
import turnos.user.domain.ports.in.GetUserNameUseCase;

import java.util.Optional;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Único lugar de reservation que conoce a user: entra por sus puertos de entrada, que solo dicen si el usuario existe
// y cuál es su nombre, y lo traduce al paciente de reservation
@Component
@RequiredArgsConstructor
public class UserDirectoryAdapter implements UserDirectory {

    private final ExistsUserByIdUseCase existsUserByIdUseCase;
    private final GetUserNameUseCase getUserNameUseCase;

    @Override
    public boolean exists(Long userId) {
        return existsUserByIdUseCase.existsUserById(userId);
    }

    @Override
    public Optional<ReservationPatient> findPatient(Long userId) {
        return getUserNameUseCase.getUserName(userId)
                .map(name -> ReservationPatient.builder()
                        .firstName(name.getFirstName())
                        .lastName(name.getLastName())
                        .build());
    }
}
