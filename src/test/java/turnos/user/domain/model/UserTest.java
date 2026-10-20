package turnos.user.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void externalPatientIdIsTheIdAsText() {
        User user = User.builder().id(1042L).build();

        assertThat(user.externalPatientId()).isEqualTo("1042");
    }

    @Test
    void externalPatientIdIsNullWhileTheUserHasNoId() {
        User user = User.builder().build();

        assertThat(user.externalPatientId()).isNull();
    }
}
