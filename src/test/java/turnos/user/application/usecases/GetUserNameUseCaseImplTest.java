package turnos.user.application.usecases;

import org.junit.jupiter.api.Test;
import turnos.user.domain.model.User;
import turnos.user.domain.model.UserName;

import static org.assertj.core.api.Assertions.assertThat;

class GetUserNameUseCaseImplTest {

    private final InMemoryUserRepository userRepository = new InMemoryUserRepository();
    private final GetUserNameUseCaseImpl getUserNameUseCase = new GetUserNameUseCaseImpl(userRepository);

    @Test
    void aRegisteredUserGivesOnlyItsFirstAndLastName() {
        User created = userRepository.create(User.builder().login("maria.gomez").email("maria.gomez@example.com")
                .passwordHash("hash").firstName("María").lastName("Gómez").build());

        UserName name = getUserNameUseCase.getUserName(created.getId()).orElseThrow();

        assertThat(name).usingRecursiveComparison()
                .isEqualTo(UserName.builder().firstName("María").lastName("Gómez").build());
    }

    @Test
    void anIdWithoutUserGivesNoName() {
        userRepository.create(User.builder().login("maria.gomez").email("maria.gomez@example.com").build());

        assertThat(getUserNameUseCase.getUserName(999L)).isEmpty();
    }
}
