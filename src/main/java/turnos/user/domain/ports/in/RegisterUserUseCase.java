package turnos.user.domain.ports.in;

import turnos.user.domain.model.User;

public interface RegisterUserUseCase {
    User registerUser(User user, String password);
}
