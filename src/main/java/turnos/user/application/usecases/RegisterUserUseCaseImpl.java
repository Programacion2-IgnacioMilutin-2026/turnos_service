package turnos.user.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.user.application.exception.UserEmailAlreadyExistsException;
import turnos.user.application.exception.UserLoginAlreadyExistsException;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;
import turnos.user.domain.ports.in.RegisterUserUseCase;
import turnos.user.domain.ports.out.PasswordHasher;
import turnos.user.domain.ports.out.UserRepository;

import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RegisterUserUseCaseImpl implements RegisterUserUseCase {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;

    @Override
    public User registerUser(User user, String password) {
        user.setLogin(user.getLogin().toLowerCase(Locale.ROOT));
        user.setEmail(user.getEmail().toLowerCase(Locale.ROOT));
        if (userRepository.existsByLogin(user.getLogin())) {
            throw new UserLoginAlreadyExistsException(user.getLogin());
        }
        if (userRepository.existsByEmail(user.getEmail())) {
            throw new UserEmailAlreadyExistsException(user.getEmail());
        }
        user.setId(null);
        user.setPasswordHash(passwordHasher.hash(password));
        user.setActivated(true);
        user.setAuthorities(Set.of(Authority.ROLE_USER));
        return userRepository.create(user);
    }

}
