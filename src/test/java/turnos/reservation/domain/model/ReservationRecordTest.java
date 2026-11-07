package turnos.reservation.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationRecordTest {

    @Test
    void aRecordNeedsItsProcessAndItsStatus() {
        assertThatThrownBy(() -> new ReservationRecord(null, ReservationRecordStatus.FAILED, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReservationRecord("process-1", null, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void everythingElseMayBeMissing() {
        ReservationRecord record = new ReservationRecord("process-1", ReservationRecordStatus.PHONE_PENDING, null, null, null, null, null, null, null);

        assertThat(record.reservationId()).isNull();
        assertThat(record.status()).isEqualTo(ReservationRecordStatus.PHONE_PENDING);
    }
}
