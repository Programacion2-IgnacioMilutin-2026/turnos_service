package turnos.user.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.user.domain.ports.in.ExistsUserByIdUseCase;
import turnos.user.domain.ports.out.UserRepository;

// Solo dice si el usuario existe: no saca del slice ningún dato del usuario
@Component
@RequiredArgsConstructor
public class ExistsUserByIdUseCaseImpl implements ExistsUserByIdUseCase {

    private final UserRepository userRepository;

    @Override
    public boolean existsUserById(Long id) {
        return userRepository.existsById(id);
    }

}
