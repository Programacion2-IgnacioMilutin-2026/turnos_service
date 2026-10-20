package turnos.user.application.service;

import org.junit.jupiter.api.Test;
import turnos.user.application.exception.UserInvalidCredentialsException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserServiceTest {

    @Test
    void returnsTheTokenWhenTheCredentialsAreValid() {
        UserService userService = new UserService((user, password) -> user, (login, password) -> Optional.of("token"));

        assertThat(userService.authenticateUser("maria.gomez", "secreta123")).isEqualTo("token");
    }

    @Test
    void turnsAMissingTokenIntoInvalidCredentials() {
        UserService userService = new UserService((user, password) -> user, (login, password) -> Optional.empty());

        assertThatThrownBy(() -> userService.authenticateUser("maria.gomez", "otra"))
                .isInstanceOf(UserInvalidCredentialsException.class)
                .hasMessage("Invalid credentials");
    }
}
