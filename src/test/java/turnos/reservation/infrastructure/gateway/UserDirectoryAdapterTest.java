package turnos.reservation.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import turnos.reservation.domain.model.ReservationPatient;
import turnos.user.domain.model.UserName;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserDirectoryAdapterTest {

    private final UserDirectoryAdapter adapter = new UserDirectoryAdapter(Set.of(42L)::contains,
            id -> Optional.ofNullable(Map.of(42L, new UserName("María", "Gómez")).get(id)));

    @Test
    void itAnswersWhetherTheUserExistsAsTheUserSliceSays() {
        assertThat(adapter.exists(42L)).isTrue();
        assertThat(adapter.exists(7L)).isFalse();
    }

    @Test
    void thePatientIsTheFirstAndLastNameOfTheUser() {
        assertThat(adapter.findPatient(42L)).get().usingRecursiveComparison()
                .isEqualTo(new ReservationPatient("María", "Gómez"));
    }

    @Test
    void aUserThatDoesNotExistHasNoPatient() {
        assertThat(adapter.findPatient(7L)).isEmpty();
    }
}
