package turnos.user.application.usecases;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;
import turnos.user.domain.ports.out.TokenIssuer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticateUserUseCaseImplTest {

    private final InMemoryUserRepository userRepository = new InMemoryUserRepository();
    private final FakePasswordHasher passwordHasher = new FakePasswordHasher();
    private final List<User> usersWithIssuedToken = new ArrayList<>();
    private final TokenIssuer tokenIssuer = user -> {
        usersWithIssuedToken.add(user);
        return "token-for-" + user.getId();
    };
    private final AuthenticateUserUseCaseImpl authenticateUserUseCase =
            new AuthenticateUserUseCaseImpl(userRepository, passwordHasher, tokenIssuer);

    @BeforeEach
    void registerUsers() {
        userRepository.create(storedUser("maria.gomez", true));
        userRepository.create(storedUser("juan.perez", false));
    }

    @Test
    void issuesATokenForTheRightCredentials() {
        assertThat(authenticateUserUseCase.authenticateUser("maria.gomez", "secreta123")).contains("token-for-1");
    }

    @Test
    void comparesTheLoginWithoutCase() {
        assertThat(authenticateUserUseCase.authenticateUser("Maria.Gomez", "secreta123")).contains("token-for-1");
    }

    @Test
    void anUnknownLoginGetsNoToken() {
        assertThat(authenticateUserUseCase.authenticateUser("nadie", "secreta123")).isEmpty();
        assertThat(usersWithIssuedToken).isEmpty();
    }

    @Test
    void aWrongPasswordGetsNoToken() {
        assertThat(authenticateUserUseCase.authenticateUser("maria.gomez", "otra")).isEmpty();
        assertThat(usersWithIssuedToken).isEmpty();
    }

    @Test
    void anInactiveAccountGetsNoTokenEvenWithTheRightPassword() {
        assertThat(authenticateUserUseCase.authenticateUser("juan.perez", "secreta123")).isEmpty();
        assertThat(usersWithIssuedToken).isEmpty();
    }

    @Test
    void aLoginOfOnlySpacesIsJustAnUnknownLogin() {
        assertThat(authenticateUserUseCase.authenticateUser("   ", "secreta123")).isEmpty();
    }

    private User storedUser(String login, boolean activated) {
        return User.builder()
                .login(login)
                .passwordHash(passwordHasher.hash("secreta123"))
                .email(login + "@example.com")
                .activated(activated)
                .authorities(Set.of(Authority.ROLE_USER))
                .build();
    }
}
