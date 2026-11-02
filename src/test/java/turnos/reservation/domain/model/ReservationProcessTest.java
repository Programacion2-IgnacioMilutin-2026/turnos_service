package turnos.reservation.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationProcessTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-10-27T12:10:00Z");
    private static final Duration MARGIN = Duration.ofSeconds(30);
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-27T12:01:00Z");

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void theStatusesWaitingForTheUserOrTheCatedraAreOpen(ReservationStatus status) {
        assertThat(process(status).isOpen()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CANCELLED", "EXPIRED", "INVALID"})
    void aProcessInAFinalStatusIsNotOpen(ReservationStatus status) {
        assertThat(process(status).isOpen()).isFalse();
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

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void aPhoneRequestLeavesTheProcessWaitingForThePhoneWithTheRequestAndItsExpiration(ReservationStatus status) {
        ReservationProcess process = process(status);
        Instant requestExpiresAt = EXPIRES_AT.plusSeconds(1);

        assertThat(process.canAcceptPhoneRequest()).isTrue();
        process.acceptPhoneRequest("request-event-1", requestExpiresAt);

        assertThat(process.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(process.getRequestEventId()).isEqualTo("request-event-1");
        assertThat(process.getExpiresAt()).isEqualTo(requestExpiresAt);
    }

    @Test
    void aNewPhoneRequestReplacesThePreviousOne() {
        ReservationProcess process = process(ReservationStatus.WAITING_FOR_PHONE);
        process.acceptPhoneRequest("request-event-1", EXPIRES_AT);

        process.acceptPhoneRequest("request-event-2", EXPIRES_AT);

        assertThat(process.getRequestEventId()).isEqualTo("request-event-2");
    }

    @Test
    void aPhoneRequestDoesNotChangeTheReconciliationMark() {
        ReservationProcess process = process(ReservationStatus.HELD);
        process.markForReconciliation();

        process.acceptPhoneRequest("request-event-1", EXPIRES_AT);

        assertThat(process.isNeedsReconciliation()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"PHONE_SUBMITTED", "EXPIRED"})
    void aProcessThatAlreadyAdvancedDoesNotAcceptAPhoneRequest(ReservationStatus status) {
        ReservationProcess process = process(status);

        assertThat(process.canAcceptPhoneRequest()).isFalse();
        assertThatThrownBy(() -> process.acceptPhoneRequest("request-event-1", EXPIRES_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(process.getStatus()).isEqualTo(status);
        assertThat(process.getRequestEventId()).isNull();
        assertThat(process.getExpiresAt()).isEqualTo(EXPIRES_AT);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void anOpenProcessBecomesAConfirmedReservation(ReservationStatus status) {
        ReservationProcess process = process(status);

        process.confirmReservation(8251L, OCCURRED_AT, null, null, null);

        assertThat(process.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(process.getReservationId()).isEqualTo(8251L);
        assertThat(process.getConfirmedAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void theConfirmedAppointmentDataReplaceTheStoredOnes() {
        ReservationProcess process = process(ReservationStatus.PHONE_SUBMITTED);

        process.confirmReservation(8251L, OCCURRED_AT, 15L, LocalDate.of(2026, 10, 20), LocalTime.of(9, 30));

        assertThat(process.getProfessionalId()).isEqualTo(15L);
        assertThat(process.getDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(process.getStartTime()).isEqualTo(LocalTime.of(9, 30));
    }

    @Test
    void theAppointmentDataThatAreNotInformedStayAsTheyWere() {
        ReservationProcess process = process(ReservationStatus.PHONE_SUBMITTED);
        process.setProfessionalId(201L);
        process.setDate(LocalDate.of(2026, 11, 2));
        process.setStartTime(LocalTime.of(12, 0));

        process.confirmReservation(8251L, OCCURRED_AT, null, null, null);

        assertThat(process.getProfessionalId()).isEqualTo(201L);
        assertThat(process.getDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(process.getStartTime()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    void aProcessWithThePhoneSubmittedGoesBackToWaitingWhenThePhoneIsRejected() {
        ReservationProcess process = process(ReservationStatus.PHONE_SUBMITTED);
        process.setRequestEventId("request-event-1");
        Instant rejectionExpiresAt = EXPIRES_AT.plusSeconds(1);

        assertThat(process.canBeRejected()).isTrue();
        process.rejectPhone("INVALID_PHONE_NUMBER", rejectionExpiresAt);

        assertThat(process.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(process.getExpiresAt()).isEqualTo(rejectionExpiresAt);
        assertThat(process.getRejectionReason()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(process.getRequestEventId()).isEqualTo("request-event-1");
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PHONE_SUBMITTED")
    void onlyAProcessWithThePhoneSubmittedCanBeRejected(ReservationStatus status) {
        ReservationProcess process = process(status);

        assertThat(process.canBeRejected()).isFalse();
        assertThatThrownBy(() -> process.rejectPhone("INVALID_PHONE_NUMBER", EXPIRES_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(process.getStatus()).isEqualTo(status);
        assertThat(process.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(process.getRejectionReason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void anOpenProcessIsClosedAsExpiredWhenTheCatedraReportsIt(ReservationStatus status) {
        ReservationProcess process = process(status);

        process.closeAsExpired();

        assertThat(process.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void anOpenProcessBecomesInvalidAndIsMarkedForReconciliation(ReservationStatus status) {
        ReservationProcess process = process(status);

        process.invalidate();

        assertThat(process.getStatus()).isEqualTo(ReservationStatus.INVALID);
        assertThat(process.isNeedsReconciliation()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CANCELLED", "EXPIRED", "INVALID"})
    void aProcessInAFinalStatusDoesNotConfirmExpireOrBecomeInvalid(ReservationStatus status) {
        ReservationProcess process = process(status);

        assertThatThrownBy(() -> process.confirmReservation(8251L, OCCURRED_AT, null, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(process::closeAsExpired).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(process::invalidate).isInstanceOf(IllegalStateException.class);
        assertThat(process.getStatus()).isEqualTo(status);
        assertThat(process.getReservationId()).isNull();
        assertThat(process.getConfirmedAt()).isNull();
        assertThat(process.isNeedsReconciliation()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED", "CONFIRMED"})
    void anOpenOrConfirmedProcessCanBeCancelled(ReservationStatus status) {
        ReservationProcess process = process(status);

        assertThat(process.canBeCancelled()).isTrue();
        process.cancel(OCCURRED_AT, "Cambio solicitado por el usuario");

        assertThat(process.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(process.getCancelledAt()).isEqualTo(OCCURRED_AT);
        assertThat(process.getCancellationReason()).isEqualTo("Cambio solicitado por el usuario");
    }

    @Test
    void aCancellationMayHaveNoReason() {
        ReservationProcess process = process(ReservationStatus.HELD);

        process.cancel(OCCURRED_AT, null);

        assertThat(process.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(process.getCancellationReason()).isNull();
    }

    @Test
    void aCancelledReservationKeepsItsConfirmation() {
        ReservationProcess process = process(ReservationStatus.PHONE_SUBMITTED);
        process.confirmReservation(8251L, OCCURRED_AT, null, null, null);

        process.cancel(OCCURRED_AT.plusSeconds(60), null);

        assertThat(process.getReservationId()).isEqualTo(8251L);
        assertThat(process.getConfirmedAt()).isEqualTo(OCCURRED_AT);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CANCELLED", "EXPIRED", "INVALID"})
    void aProcessThatEndedWithoutAReservationCannotBeCancelled(ReservationStatus status) {
        ReservationProcess process = process(status);

        assertThat(process.canBeCancelled()).isFalse();
        assertThatThrownBy(() -> process.cancel(OCCURRED_AT, "reason")).isInstanceOf(IllegalStateException.class);
        assertThat(process.getStatus()).isEqualTo(status);
        assertThat(process.getCancelledAt()).isNull();
        assertThat(process.getCancellationReason()).isNull();
    }

    @ParameterizedTest
    @MethodSource("exitsFromWaitingForThePhone")
    void leavingWaitingForThePhoneClearsTheRejectionReason(Consumer<ReservationProcess> exit) {
        ReservationProcess process = rejected();

        exit.accept(process);

        assertThat(process.getRejectionReason()).isNull();
    }

    @ParameterizedTest
    @MethodSource("exitsToConfirmedCancelledOrExpired")
    void reachingConfirmedCancelledOrExpiredTurnsTheMarkOff(Consumer<ReservationProcess> exit) {
        ReservationProcess process = process(ReservationStatus.WAITING_FOR_PHONE);
        process.markForReconciliation();

        exit.accept(process);

        assertThat(process.isNeedsReconciliation()).isFalse();
    }

    @Test
    void aRejectionDoesNotChangeTheMark() {
        ReservationProcess process = process(ReservationStatus.PHONE_SUBMITTED);
        process.markForReconciliation();

        process.rejectPhone("INVALID_PHONE_NUMBER", EXPIRES_AT);

        assertThat(process.isNeedsReconciliation()).isTrue();
    }

    @Test
    void aMarkedProcessConfirmedByTheUserKeepsTheMark() {
        ReservationProcess process = process(ReservationStatus.HELD);
        process.markForReconciliation();

        process.confirm(EXPIRES_AT);

        assertThat(process.isNeedsReconciliation()).isTrue();
    }

    @Test
    void aConfirmedProcessCanBeMarkedAgainAfterClosing() {
        ReservationProcess process = process(ReservationStatus.WAITING_FOR_PHONE);
        process.markForReconciliation();
        process.confirmReservation(8251L, OCCURRED_AT, null, null, null);

        process.markForReconciliation();

        assertThat(process.isNeedsReconciliation()).isTrue();
        assertThat(process.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void aReasonUpToTheLimitIsKeptWhole() {
        String reason = "a".repeat(ReservationProcess.MAX_REASON_LENGTH);
        ReservationProcess rejected = process(ReservationStatus.PHONE_SUBMITTED);
        ReservationProcess cancelled = process(ReservationStatus.HELD);

        rejected.rejectPhone(reason, EXPIRES_AT);
        cancelled.cancel(OCCURRED_AT, reason);

        assertThat(rejected.getRejectionReason()).isEqualTo(reason);
        assertThat(cancelled.getCancellationReason()).isEqualTo(reason);
    }

    @Test
    void aLongerReasonIsCutAtTheLimit() {
        String reason = "a".repeat(ReservationProcess.MAX_REASON_LENGTH + 1);
        ReservationProcess rejected = process(ReservationStatus.PHONE_SUBMITTED);
        ReservationProcess cancelled = process(ReservationStatus.HELD);

        rejected.rejectPhone(reason, EXPIRES_AT);
        cancelled.cancel(OCCURRED_AT, reason);

        assertThat(rejected.getRejectionReason()).isEqualTo(reason.substring(0, ReservationProcess.MAX_REASON_LENGTH));
        assertThat(cancelled.getCancellationReason()).isEqualTo(reason.substring(0, ReservationProcess.MAX_REASON_LENGTH));
    }

    @Test
    void cuttingAReasonDoesNotSplitACharacter() {
        String reason = "a".repeat(ReservationProcess.MAX_REASON_LENGTH - 1) + "😀" + "b";
        ReservationProcess process = process(ReservationStatus.HELD);

        process.cancel(OCCURRED_AT, reason);

        assertThat(process.getCancellationReason()).isEqualTo("a".repeat(ReservationProcess.MAX_REASON_LENGTH - 1));
    }

    private static Stream<Consumer<ReservationProcess>> exitsFromWaitingForThePhone() {
        return Stream.concat(exitsToConfirmedCancelledOrExpired(), Stream.of(ReservationProcess::invalidate));
    }

    private static Stream<Consumer<ReservationProcess>> exitsToConfirmedCancelledOrExpired() {
        return Stream.of(
                p -> p.confirmReservation(8251L, OCCURRED_AT, null, null, null),
                p -> p.cancel(OCCURRED_AT, null),
                ReservationProcess::closeAsExpired,
                ReservationProcess::expire,
                p -> p.expireIfOverdue(EXPIRES_AT.plusSeconds(31), MARGIN));
    }

    private static ReservationProcess rejected() {
        ReservationProcess process = process(ReservationStatus.PHONE_SUBMITTED);
        process.rejectPhone("INVALID_PHONE_NUMBER", EXPIRES_AT);
        return process;
    }

    private static ReservationProcess process(ReservationStatus status) {
        return ReservationProcess.builder().reservationProcessId("process-1").status(status).expiresAt(EXPIRES_AT).build();
    }
}
