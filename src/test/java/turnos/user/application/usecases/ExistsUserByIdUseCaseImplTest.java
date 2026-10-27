package turnos.user.application.usecases;

import org.junit.jupiter.api.Test;
import turnos.user.domain.model.User;

import static org.assertj.core.api.Assertions.assertThat;

class ExistsUserByIdUseCaseImplTest {

    private final InMemoryUserRepository userRepository = new InMemoryUserRepository();
    private final ExistsUserByIdUseCaseImpl existsUserByIdUseCase = new ExistsUserByIdUseCaseImpl(userRepository);

    @Test
    void aRegisteredUserExists() {
        User created = userRepository.create(User.builder().login("maria.gomez").email("maria.gomez@example.com").build());

        assertThat(existsUserByIdUseCase.existsUserById(created.getId())).isTrue();
    }

    @Test
    void anIdWithoutUserDoesNotExist() {
        userRepository.create(User.builder().login("maria.gomez").email("maria.gomez@example.com").build());

        assertThat(existsUserByIdUseCase.existsUserById(999L)).isFalse();
    }
}
