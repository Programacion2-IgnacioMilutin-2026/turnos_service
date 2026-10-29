package turnos.reservation.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationProcessTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-10-27T12:10:00Z");
    private static final Duration MARGIN = Duration.ofSeconds(30);

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void theStatusesWaitingForTheUserOrTheCatedraAreOpen(ReservationStatus status) {
        assertThat(process(status).isOpen()).isTrue();
    }

    @Test
    void anExpiredProcessIsNotOpen() {
        assertThat(process(ReservationStatus.EXPIRED).isOpen()).isFalse();
    }

    @Test
    void itExpiresOnlyWhenTheMarginAfterTheCatedraExpirationHasPassed() {
        ReservationProcess process = process(ReservationStatus.HELD);

        assertThat(process.hasExpired(EXPIRES_AT.plusSeconds(29), MARGIN)).isFalse();
        assertThat(process.hasExpired(EXPIRES_AT.plusSeconds(30), MARGIN)).isFalse();
        assertThat(process.hasExpired(EXPIRES_AT.plusSeconds(31), MARGIN)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void aProcessWaitingForTheUserIsClosedWhenItExpires(ReservationStatus status) {
        ReservationProcess process = process(status);

        assertThat(process.expireIfOverdue(EXPIRES_AT.plusSeconds(31), MARGIN)).isTrue();
        assertThat(process.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void aProcessIsNotClosedBeforeItExpires() {
        ReservationProcess process = process(ReservationStatus.HELD);

        assertThat(process.expireIfOverdue(EXPIRES_AT.plusSeconds(29), MARGIN)).isFalse();
        assertThat(process.getStatus()).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void aProcessWithThePhoneSubmittedIsNotClosedByTheClock() {
        ReservationProcess process = process(ReservationStatus.PHONE_SUBMITTED);

        assertThat(process.expireIfOverdue(EXPIRES_AT.plusSeconds(3600), MARGIN)).isFalse();
        assertThat(process.getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
    }

    @Test
    void aHeldProcessConfirmedWaitsForThePhoneUntilTheExpirationOfTheCatedra() {
        ReservationProcess process = process(ReservationStatus.HELD);
        Instant confirmedExpiresAt = EXPIRES_AT.plusSeconds(5);

        process.confirm(confirmedExpiresAt);

        assertThat(process.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(process.getExpiresAt()).isEqualTo(confirmedExpiresAt);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"WAITING_FOR_PHONE", "PHONE_SUBMITTED", "EXPIRED"})
    void onlyAHeldProcessCanBeConfirmed(ReservationStatus status) {
        ReservationProcess process = process(status);

        assertThatThrownBy(() -> process.confirm(EXPIRES_AT.plusSeconds(5))).isInstanceOf(IllegalStateException.class);
        assertThat(process.getStatus()).isEqualTo(status);
        assertThat(process.getExpiresAt()).isEqualTo(EXPIRES_AT);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void aProcessWaitingForTheUserExpiresWhenTheCatedraSaysSo(ReservationStatus status) {
        ReservationProcess process = process(status);

        process.expire();

        assertThat(process.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"PHONE_SUBMITTED", "EXPIRED"})
    void aProcessThatIsNotWaitingForTheUserDoesNotExpire(ReservationStatus status) {
        ReservationProcess process = process(status);

        assertThatThrownBy(process::expire).isInstanceOf(IllegalStateException.class);
        assertThat(process.getStatus()).isEqualTo(status);
    }

    @Test
    void aProcessIsNotMarkedForReconciliationUntilAskedTo() {
        ReservationProcess process = process(ReservationStatus.HELD);
        assertThat(process.isNeedsReconciliation()).isFalse();

        process.markForReconciliation();

        assertThat(process.isNeedsReconciliation()).isTrue();
        assertThat(process.getStatus()).isEqualTo(ReservationStatus.HELD);
    }

    private static ReservationProcess process(ReservationStatus status) {
        return ReservationProcess.builder().reservationProcessId("process-1").status(status).expiresAt(EXPIRES_AT).build();
    }
}
