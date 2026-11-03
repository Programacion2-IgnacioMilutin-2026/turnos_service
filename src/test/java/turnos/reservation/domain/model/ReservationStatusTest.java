package turnos.reservation.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationStatusTest {

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED", "CONFIRMED", "CANCELLED"})
    void theOpenConfirmedAndCancelledStatusesCanBeListed(ReservationStatus status) {
        assertThat(status.isListable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"EXPIRED", "INVALID"})
    void anAttemptThatCameToNothingIsNotListed(ReservationStatus status) {
        assertThat(status.isListable()).isFalse();
    }
}
