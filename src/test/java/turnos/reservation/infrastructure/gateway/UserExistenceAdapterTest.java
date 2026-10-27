package turnos.reservation.infrastructure.gateway;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserExistenceAdapterTest {

    @Test
    void itAnswersWhatTheUserSliceSays() {
        UserExistenceAdapter adapter = new UserExistenceAdapter(Set.of(42L)::contains);

        assertThat(adapter.exists(42L)).isTrue();
        assertThat(adapter.exists(7L)).isFalse();
    }
}
