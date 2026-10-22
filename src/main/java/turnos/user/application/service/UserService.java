package turnos.user.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import turnos.user.application.exception.UserInvalidCredentialsException;
import turnos.user.domain.model.User;
import turnos.user.domain.ports.in.AuthenticateUserUseCase;
import turnos.user.domain.ports.in.RegisterUserUseCase;

@Service
@RequiredArgsConstructor
public class UserService {

    private final RegisterUserUseCase registerUserUseCase;
    private final AuthenticateUserUseCase authenticateUserUseCase;

    public User registerUser(User user, String password) {
        return registerUserUseCase.registerUser(user, password);
    }

    public String authenticateUser(String login, String password) {
        return authenticateUserUseCase.authenticateUser(login, password)
                .orElseThrow(UserInvalidCredentialsException::new);
    }

}
