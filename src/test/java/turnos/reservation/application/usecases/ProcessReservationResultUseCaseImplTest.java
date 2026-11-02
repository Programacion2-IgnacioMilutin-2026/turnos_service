package turnos.reservation.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.MutableClock;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationEventOutcome;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationResult;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.TransactionRunner;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ProcessReservationResultUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-11-02T13:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(600);
    private static final Instant OCCURRED_AT = NOW.minusSeconds(5);
    private static final Long USER = 42L;
    private static final String PROCESS = "process-1";
    private static final String EVENT = "event-1";
    private static final String HOLD = "hold-1";
    private static final Long PROFESSIONAL = 201L;
    private static final LocalDate DATE = LocalDate.of(2026, 11, 9);
    private static final LocalTime START_TIME = LocalTime.of(10, 30);

    private final CopyingReservationProcessRepository processes = new CopyingReservationProcessRepository();
    private final InMemoryProcessedEventRepository events = new InMemoryProcessedEventRepository();
    private final UserOperationLock userOperationLock = new UserOperationLock();
    private final ProcessReservationResultUseCaseImpl useCase = useCase(Runnable::run);

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void aConfirmationTurnsAnOpenProcessIntoAReservationAndIsRegistered(ReservationStatus status) {
        processes.add(process(status));

        assertThat(useCase.processReservationResult(confirmed())).isEqualTo(ReservationEventOutcome.APPLIED);

        ReservationProcess stored = processes.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(stored.getReservationId()).isEqualTo(8251L);
        assertThat(stored.getConfirmedAt()).isEqualTo(OCCURRED_AT);
        assertThat(events.registered()).containsExactly(Map.entry(EVENT, NOW));
    }

    @Test
    void aConfirmationWithOtherAppointmentDataReplacesThemAndWarns(CapturedOutput output) {
        processes.add(process(ReservationStatus.PHONE_SUBMITTED));
        LocalDate otherDate = DATE.plusDays(1);

        useCase.processReservationResult(new ReservationResult.Confirmed(EVENT, PROCESS, HOLD, 8251L, OCCURRED_AT,
                15L, otherDate, START_TIME.plusHours(1)));

        ReservationProcess stored = processes.stored(PROCESS);
        assertThat(stored.getProfessionalId()).isEqualTo(15L);
        assertThat(stored.getDate()).isEqualTo(otherDate);
        assertThat(stored.getStartTime()).isEqualTo(START_TIME.plusHours(1));
        assertThat(output.getAll()).contains("WARN").contains("Catedra confirmed professional 15");
    }

    @Test
    void aConfirmationWithTheSameAppointmentDataDoesNotWarn(CapturedOutput output) {
        processes.add(process(ReservationStatus.PHONE_SUBMITTED));

        useCase.processReservationResult(new ReservationResult.Confirmed(EVENT, PROCESS, HOLD, 8251L, OCCURRED_AT,
                PROFESSIONAL, DATE, START_TIME));

        assertThat(output.getAll()).doesNotContain("Catedra confirmed professional");
    }

    @Test
    void aConfirmationWithoutAppointmentDataKeepsTheStoredOnesAndDoesNotWarn(CapturedOutput output) {
        processes.add(process(ReservationStatus.PHONE_SUBMITTED));

        useCase.processReservationResult(confirmed());

        ReservationProcess stored = processes.stored(PROCESS);
        assertThat(stored.getProfessionalId()).isEqualTo(PROFESSIONAL);
        assertThat(stored.getDate()).isEqualTo(DATE);
        assertThat(stored.getStartTime()).isEqualTo(START_TIME);
        assertThat(output.getAll()).doesNotContain("Catedra confirmed professional");
    }

    @Test
    void aRejectionSendsTheProcessBackToWaitingWithTheReasonAndTheNewExpiration() {
        ReservationProcess submitted = process(ReservationStatus.PHONE_SUBMITTED);
        submitted.setRequestEventId("request-event-1");
        processes.add(submitted);
        Instant rejectionExpiresAt = EXPIRES_AT.plusSeconds(1);

        assertThat(useCase.processReservationResult(new ReservationResult.Rejected(EVENT, PROCESS, HOLD,
                "INVALID_PHONE_NUMBER", rejectionExpiresAt))).isEqualTo(ReservationEventOutcome.APPLIED);

        ReservationProcess stored = processes.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(stored.getExpiresAt()).isEqualTo(rejectionExpiresAt);
        assertThat(stored.getRejectionReason()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(stored.getRequestEventId()).isEqualTo("request-event-1");
        assertThat(events.registered()).containsKey(EVENT);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void aRejectionWithoutASubmittedPhoneChangesNothingButIsRegistered(ReservationStatus status, CapturedOutput output) {
        ReservationProcess process = process(status);
        process.setRejectionReason("PREVIOUS_REASON");
        processes.add(process);

        assertThat(useCase.processReservationResult(rejected())).isEqualTo(ReservationEventOutcome.STATE_ADVANCED);

        ReservationProcess stored = processes.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(status);
        assertThat(stored.getRejectionReason()).isEqualTo("PREVIOUS_REASON");
        assertThat(stored.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(processes.history(PROCESS)).isEmpty();
        assertThat(events.registered()).containsKey(EVENT);
        assertThat(output.getAll()).contains("Reservation result event-1 ignored");
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void anExpirationClosesAnOpenProcess(ReservationStatus status) {
        processes.add(process(status));

        assertThat(useCase.processReservationResult(expired())).isEqualTo(ReservationEventOutcome.APPLIED);

        assertThat(processes.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(events.registered()).containsKey(EVENT);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void anInvalidProcessIsClosedAndMarkedForReconciliation(ReservationStatus status, CapturedOutput output) {
        processes.add(process(status));

        assertThat(useCase.processReservationResult(invalid())).isEqualTo(ReservationEventOutcome.APPLIED);

        ReservationProcess stored = processes.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.INVALID);
        assertThat(stored.isNeedsReconciliation()).isTrue();
        assertThat(events.registered()).containsKey(EVENT);
        assertThat(output.getAll()).contains("REQUEST_EVENT_MISMATCH");
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED", "CONFIRMED"})
    void aCancellationClosesAnOpenOrConfirmedProcess(ReservationStatus status) {
        processes.add(process(status));

        assertThat(useCase.processReservationResult(cancelled("Cambio solicitado por el usuario")))
                .isEqualTo(ReservationEventOutcome.APPLIED);

        ReservationProcess stored = processes.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(stored.getCancelledAt()).isEqualTo(OCCURRED_AT);
        assertThat(stored.getCancellationReason()).isEqualTo("Cambio solicitado por el usuario");
        assertThat(events.registered()).containsKey(EVENT);
    }

    @Test
    void aCancellationWithoutAReasonIsApplied(CapturedOutput output) {
        processes.add(process(ReservationStatus.WAITING_FOR_PHONE));

        assertThat(useCase.processReservationResult(cancelled(null))).isEqualTo(ReservationEventOutcome.APPLIED);

        assertThat(processes.stored(PROCESS).getCancellationReason()).isNull();
        assertThat(output.getAll()).doesNotContain("could not be saved as it came");
    }

    @Test
    void aCancelledReservationKeepsItsConfirmation() {
        ReservationProcess confirmedProcess = process(ReservationStatus.CONFIRMED);
        confirmedProcess.setReservationId(8251L);
        confirmedProcess.setConfirmedAt(OCCURRED_AT);
        processes.add(confirmedProcess);

        useCase.processReservationResult(new ReservationResult.Cancelled(EVENT, PROCESS, HOLD, NOW, null));

        ReservationProcess stored = processes.stored(PROCESS);
        assertThat(stored.getReservationId()).isEqualTo(8251L);
        assertThat(stored.getConfirmedAt()).isEqualTo(OCCURRED_AT);
        assertThat(stored.getCancelledAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @MethodSource("resultsOverFinalStatuses")
    void aResultDoesNotReopenAFinalStatusButIsRegistered(ReservationStatus status, ReservationResult result) {
        ReservationProcess process = process(status);
        processes.add(process);

        assertThat(useCase.processReservationResult(result)).isEqualTo(ReservationEventOutcome.STATE_ADVANCED);

        assertThat(processes.stored(PROCESS)).usingRecursiveComparison().isEqualTo(process);
        assertThat(processes.history(PROCESS)).isEmpty();
        assertThat(events.registered()).containsKey(EVENT);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"EXPIRED", "INVALID"})
    void aConfirmationOverAProcessClosedWithoutItIsReportedAsAnError(ReservationStatus status, CapturedOutput output) {
        processes.add(process(status));

        useCase.processReservationResult(confirmed());

        assertThat(output.getAll()).contains("ERROR")
                .contains("Catedra confirmed reservation 8251 of reservation process process-1, which is already " + status);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CANCELLED"})
    void aConfirmationOverOtherFinalStatusesIsNotAnError(ReservationStatus status, CapturedOutput output) {
        processes.add(process(status));

        useCase.processReservationResult(confirmed());

        assertThat(output.getAll()).doesNotContain("ERROR").contains("Reservation result event-1 ignored");
    }

    @Test
    void anEventAlreadyProcessedRepeatsNothing() {
        processes.add(process(ReservationStatus.PHONE_SUBMITTED));
        useCase.processReservationResult(confirmed());

        assertThat(useCase.processReservationResult(confirmed())).isEqualTo(ReservationEventOutcome.ALREADY_PROCESSED);

        assertThat(processes.history(PROCESS)).containsExactly(ReservationStatus.CONFIRMED);
    }

    @Test
    void anEventRepeatedAfterTheProcessChangedDoesNotModifyIt() {
        processes.add(process(ReservationStatus.PHONE_SUBMITTED));
        useCase.processReservationResult(rejected());
        ReservationProcess changed = processes.stored(PROCESS);
        changed.setStatus(ReservationStatus.PHONE_SUBMITTED);
        changed.setRejectionReason(null);
        processes.update(PROCESS, changed);

        assertThat(useCase.processReservationResult(rejected())).isEqualTo(ReservationEventOutcome.ALREADY_PROCESSED);

        assertThat(processes.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(processes.stored(PROCESS).getRejectionReason()).isNull();
    }

    @Test
    void aResultOfAnUnknownProcessIsAnOrphanAndIsNotRegistered(CapturedOutput output) {
        assertThat(useCase.processReservationResult(confirmed())).isEqualTo(ReservationEventOutcome.ORPHAN);

        assertThat(events.registered()).isEmpty();
        assertThat(output.getAll()).contains("Reservation result event-1 refers to reservation process process-1, which does not exist");
    }

    @Test
    void aResultWithAnotherHoldChangesNothingAndIsNotRegistered(CapturedOutput output) {
        processes.add(process(ReservationStatus.PHONE_SUBMITTED));

        assertThat(useCase.processReservationResult(new ReservationResult.Expired(EVENT, PROCESS, "hold-other")))
                .isEqualTo(ReservationEventOutcome.HOLD_MISMATCH);

        assertThat(processes.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(processes.history(PROCESS)).isEmpty();
        assertThat(events.registered()).isEmpty();
        assertThat(output.getAll()).contains("Reservation result event-1 has hold hold-other");
    }

    @Test
    void aReasonThatDoesNotFitIsSavedCutAndWarns(CapturedOutput output) {
        processes.add(process(ReservationStatus.WAITING_FOR_PHONE));
        String reason = "a".repeat(ReservationProcess.MAX_REASON_LENGTH + 1);

        assertThat(useCase.processReservationResult(cancelled(reason))).isEqualTo(ReservationEventOutcome.APPLIED);

        assertThat(processes.stored(PROCESS).getCancellationReason()).hasSize(ReservationProcess.MAX_REASON_LENGTH);
        assertThat(output.getAll()).contains("WARN").contains("could not be saved as it came").contains(reason);
    }

    @Test
    void aRejectionReasonWithTheNullCharacterIsSavedWithoutItAndWarns(CapturedOutput output) {
        processes.add(process(ReservationStatus.PHONE_SUBMITTED));

        useCase.processReservationResult(new ReservationResult.Rejected(EVENT, PROCESS, HOLD, "INVALID\0_PHONE", EXPIRES_AT));

        assertThat(processes.stored(PROCESS).getRejectionReason()).isEqualTo("INVALID_PHONE");
        assertThat(output.getAll()).contains("WARN").contains("could not be saved as it came");
    }

    @Test
    void ifTheDatabaseFailsTheFailureArrivesAndNothingChanges() {
        processes.add(process(ReservationStatus.PHONE_SUBMITTED));
        ProcessReservationResultUseCaseImpl failing = useCase(work -> {
            throw new ReservationNotSavedException(new IllegalStateException("database down"));
        });

        assertThatThrownBy(() -> failing.processReservationResult(confirmed()))
                .isInstanceOf(ReservationNotSavedException.class);

        assertThat(processes.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(events.registered()).isEmpty();
    }

    @Test
    void ifTheDatabaseFailsRegisteringAnIgnoredResultTheFailureArrives() {
        processes.add(process(ReservationStatus.EXPIRED));
        ProcessReservationResultUseCaseImpl failing = useCase(work -> {
            throw new ReservationNotSavedException(new IllegalStateException("database down"));
        });

        assertThatThrownBy(() -> failing.processReservationResult(expired()))
                .isInstanceOf(ReservationNotSavedException.class);

        assertThat(events.registered()).isEmpty();
    }

    @Test
    void theResultWaitsForAnOperationOfTheSameUserAndNeitherWriteOverwritesTheOther() throws Exception {
        processes.add(process(ReservationStatus.HELD));
        processes.add(ReservationProcess.builder().reservationProcessId("process-2").holdId(HOLD).userId(7L)
                .professionalId(PROFESSIONAL).date(DATE).startTime(START_TIME).status(ReservationStatus.HELD)
                .expiresAt(EXPIRES_AT).build());
        CountDownLatch lockTaken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Instant confirmedExpiresAt = EXPIRES_AT.plusSeconds(1);
        // Como la confirmación del usuario: lee, espera a la cátedra y guarda su copia
        Thread operationOfTheUser = Thread.ofVirtual().start(() -> userOperationLock.exclusivelyFor(USER, () -> {
            ReservationProcess read = processes.stored(PROCESS);
            lockTaken.countDown();
            await(release);
            read.confirm(confirmedExpiresAt);
            return processes.update(PROCESS, read);
        }));
        assertThat(lockTaken.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ReservationEventOutcome> sameUser = CompletableFuture.supplyAsync(
                () -> useCase.processReservationResult(cancelled("reason")));
        ReservationEventOutcome otherUser = CompletableFuture.supplyAsync(() -> useCase.processReservationResult(
                new ReservationResult.Expired("event-2", "process-2", HOLD))).get(5, TimeUnit.SECONDS);

        assertThat(otherUser).isEqualTo(ReservationEventOutcome.APPLIED);
        Thread.sleep(200);
        assertThat(sameUser).isNotDone();

        release.countDown();
        assertThat(sameUser.get(5, TimeUnit.SECONDS)).isEqualTo(ReservationEventOutcome.APPLIED);
        operationOfTheUser.join(5000);
        assertThat(processes.history(PROCESS))
                .containsExactly(ReservationStatus.WAITING_FOR_PHONE, ReservationStatus.CANCELLED);
        ReservationProcess stored = processes.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(stored.getExpiresAt()).isEqualTo(confirmedExpiresAt);
    }

    @Test
    void aProcessClosedWhileTheResultWaitedIsNotReopened() throws Exception {
        processes.add(process(ReservationStatus.PHONE_SUBMITTED));
        CountDownLatch lockTaken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread operationOfTheUser = Thread.ofVirtual().start(() -> userOperationLock.exclusivelyFor(USER, () -> {
            lockTaken.countDown();
            await(release);
            ReservationProcess closed = processes.stored(PROCESS);
            closed.closeAsExpired();
            return processes.update(PROCESS, closed);
        }));
        assertThat(lockTaken.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ReservationEventOutcome> result = CompletableFuture.supplyAsync(
                () -> useCase.processReservationResult(rejected()));
        Thread.sleep(200);
        release.countDown();

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(ReservationEventOutcome.STATE_ADVANCED);
        operationOfTheUser.join(5000);
        assertThat(processes.history(PROCESS)).containsExactly(ReservationStatus.EXPIRED);
        assertThat(events.registered()).containsKey(EVENT);
    }

    private static Stream<Arguments> resultsOverFinalStatuses() {
        List<ReservationStatus> finalStatuses = Arrays.stream(ReservationStatus.values()).filter(s -> !s.isOpen()).toList();
        List<ReservationResult> results = List.of(confirmed(), rejected(), expired(), invalid(), cancelled("reason"));
        return finalStatuses.stream().flatMap(status -> results.stream()
                .filter(result -> !(status == ReservationStatus.CONFIRMED && result instanceof ReservationResult.Cancelled))
                .map(result -> Arguments.of(status, result)));
    }

    private ProcessReservationResultUseCaseImpl useCase(TransactionRunner transactionRunner) {
        return new ProcessReservationResultUseCaseImpl(processes, events, transactionRunner, userOperationLock,
                new MutableClock(NOW));
    }

    private static ReservationResult.Confirmed confirmed() {
        return new ReservationResult.Confirmed(EVENT, PROCESS, HOLD, 8251L, OCCURRED_AT, null, null, null);
    }

    private static ReservationResult.Rejected rejected() {
        return new ReservationResult.Rejected(EVENT, PROCESS, HOLD, "INVALID_PHONE_NUMBER", EXPIRES_AT.plusSeconds(1));
    }

    private static ReservationResult.Expired expired() {
        return new ReservationResult.Expired(EVENT, PROCESS, HOLD);
    }

    private static ReservationResult.Invalid invalid() {
        return new ReservationResult.Invalid(EVENT, PROCESS, HOLD, "REQUEST_EVENT_MISMATCH");
    }

    private static ReservationResult.Cancelled cancelled(String reason) {
        return new ReservationResult.Cancelled(EVENT, PROCESS, HOLD, OCCURRED_AT, reason);
    }

    private static ReservationProcess process(ReservationStatus status) {
        return ReservationProcess.builder().reservationProcessId(PROCESS).holdId(HOLD).userId(USER)
                .professionalId(PROFESSIONAL).date(DATE).startTime(START_TIME).status(status).expiresAt(EXPIRES_AT).build();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
