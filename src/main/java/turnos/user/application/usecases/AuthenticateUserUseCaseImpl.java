package turnos.user.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.user.domain.model.User;
import turnos.user.domain.ports.in.AuthenticateUserUseCase;
import turnos.user.domain.ports.out.PasswordHasher;
import turnos.user.domain.ports.out.TokenIssuer;
import turnos.user.domain.ports.out.UserRepository;

import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AuthenticateUserUseCaseImpl implements AuthenticateUserUseCase {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenIssuer tokenIssuer;

    // Login inexistente, cuenta inactiva y contraseña incorrecta terminan igual: vacío, sin decir cuál falló
    @Override
    public Optional<String> authenticateUser(String login, String password) {
        return userRepository.findByLogin(login.toLowerCase(Locale.ROOT))
                .filter(User::isActivated)
                .filter(user -> passwordHasher.matches(password, user.getPasswordHash()))
                .map(tokenIssuer::issue);
    }

}
