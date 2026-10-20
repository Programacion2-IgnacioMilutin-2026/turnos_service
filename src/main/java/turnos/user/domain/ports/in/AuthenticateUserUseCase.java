package turnos.user.domain.ports.in;

import java.util.Optional;

public interface AuthenticateUserUseCase {
    Optional<String> authenticateUser(String login, String password);
}
