package turnos.reservation.infrastructure.gateway;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.reservation.domain.ports.out.UserDirectory;
import turnos.user.domain.ports.in.ExistsUserByIdUseCase;

// Paquete infrastructure/gateway: excepción a la skill autorizada por el usuario.
// Único lugar de reservation que conoce a user: entra por su puerto de entrada, que solo responde si el usuario existe
@Component
@RequiredArgsConstructor
public class UserExistenceAdapter implements UserDirectory {

    private final ExistsUserByIdUseCase existsUserByIdUseCase;

    @Override
    public boolean exists(Long userId) {
        return existsUserByIdUseCase.existsUserById(userId);
    }
}
