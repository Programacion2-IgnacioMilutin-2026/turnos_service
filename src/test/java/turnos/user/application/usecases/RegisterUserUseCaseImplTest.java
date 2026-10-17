package turnos.user.application.usecases;

import org.junit.jupiter.api.Test;
import turnos.user.application.exception.UserEmailAlreadyExistsException;
import turnos.user.application.exception.UserLoginAlreadyExistsException;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;
import turnos.user.domain.ports.out.PasswordHasher;
import turnos.user.domain.ports.out.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisterUserUseCaseImplTest {

    private final InMemoryUserRepository userRepository = new InMemoryUserRepository();
    private final PasswordHasher passwordHasher = password -> "hashed:" + password;
    private final RegisterUserUseCaseImpl registerUserUseCase = new RegisterUserUseCaseImpl(userRepository, passwordHasher);

    @Test
    void storesLoginAndEmailInLowerCase() {
        User registered = registerUserUseCase.registerUser(newUser("Maria.Gomez", "Maria.Gomez@Example.com"), "secreta123");

        assertThat(registered.getLogin()).isEqualTo("maria.gomez");
        assertThat(registered.getEmail()).isEqualTo("maria.gomez@example.com");
    }

    @Test
    void storesTheHashAndNeverThePassword() {
        User registered = registerUserUseCase.registerUser(newUser("maria.gomez", "maria.gomez@example.com"), "secreta123");

        assertThat(registered.getPasswordHash()).isEqualTo("hashed:secreta123");
    }

    @Test
    void createsTheUserActivatedAsCommonUserIgnoringWhatItReceived() {
        User user = newUser("maria.gomez", "maria.gomez@example.com");
        user.setId(999L);
        user.setActivated(false);
        user.setAuthorities(Set.of(Authority.ROLE_ADMIN));

        User registered = registerUserUseCase.registerUser(user, "secreta123");

        assertThat(userRepository.idsReceivedOnCreate).containsExactly((Long) null);
        assertThat(registered.isActivated()).isTrue();
        assertThat(registered.getAuthorities()).containsExactly(Authority.ROLE_USER);
    }

    @Test
    void rejectsARepeatedLoginEvenWithDifferentCase() {
        registerUserUseCase.registerUser(newUser("maria.gomez", "maria.gomez@example.com"), "secreta123");

        assertThatThrownBy(() -> registerUserUseCase.registerUser(newUser("MARIA.GOMEZ", "otra@example.com"), "secreta123"))
                .isInstanceOf(UserLoginAlreadyExistsException.class);
        assertThat(userRepository.users).hasSize(1);
    }

    @Test
    void rejectsARepeatedEmailEvenWithDifferentCase() {
        registerUserUseCase.registerUser(newUser("maria.gomez", "maria.gomez@example.com"), "secreta123");

        assertThatThrownBy(() -> registerUserUseCase.registerUser(newUser("otro.login", "MARIA.GOMEZ@EXAMPLE.COM"), "secreta123"))
                .isInstanceOf(UserEmailAlreadyExistsException.class);
        assertThat(userRepository.users).hasSize(1);
    }

    private User newUser(String login, String email) {
        return User.builder()
                .login(login)
                .firstName("María")
                .lastName("Gómez")
                .email(email)
                .langKey("es")
                .build();
    }

    // Busca con igualdad exacta, como la base: así el test comprueba que las minúsculas las pone el caso de uso
    private static class InMemoryUserRepository implements UserRepository {

        private final List<User> users = new ArrayList<>();
        private final List<Long> idsReceivedOnCreate = new ArrayList<>();

        @Override
        public User create(User user) {
            idsReceivedOnCreate.add(user.getId());
            user.setId((long) users.size() + 1);
            users.add(user);
            return user;
        }

        @Override
        public Optional<User> findByLogin(String login) {
            return users.stream().filter(user -> user.getLogin().equals(login)).findFirst();
        }

        @Override
        public boolean existsByLogin(String login) {
            return findByLogin(login).isPresent();
        }

        @Override
        public boolean existsByEmail(String email) {
            return users.stream().anyMatch(user -> user.getEmail().equals(email));
        }
    }
}
