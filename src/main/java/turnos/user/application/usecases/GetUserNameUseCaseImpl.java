package turnos.user.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.user.domain.model.UserName;
import turnos.user.domain.ports.in.GetUserNameUseCase;
import turnos.user.domain.ports.out.UserRepository;

import java.util.Optional;

// Lee el usuario completo, pero solo entrega su nombre y apellido
@Component
@RequiredArgsConstructor
public class GetUserNameUseCaseImpl implements GetUserNameUseCase {

    private final UserRepository userRepository;

    @Override
    public Optional<UserName> getUserName(Long id) {
        return userRepository.findById(id)
                .map(user -> UserName.builder()
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .build());
    }
}
