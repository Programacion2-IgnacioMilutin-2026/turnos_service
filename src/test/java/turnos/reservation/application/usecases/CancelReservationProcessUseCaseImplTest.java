package turnos.reservation.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.MutableClock;
import turnos.reservation.application.exception.ReservationAppointmentNotFoundException;
import turnos.reservation.application.exception.ReservationAppointmentOwnerMismatchException;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationInvalidProcessStateException;
import turnos.reservation.application.exception.ReservationLocalStateOutOfSyncException;
import turnos.reservation.application.exception.ReservationNotCancellableException;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.AppointmentCanceller;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CancelReservationProcessUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-11-04T13:00:00Z");
    private static final Instant CANCELLED_AT = Instant.parse("2026-11-04T13:00:05Z");
    private static final Long USER = 42L;
    private static final Long OTHER_USER = 7L;
    private static final String PROCESS = "process-1";
    private static final String PHONE = "+5492615551234";
    private static final String REASON = "Cambio solicitado por el usuario";

    private final CopyingReservationProcessRepository repository = new CopyingReservationProcessRepository();
    private final ScriptedCanceller canceller = new ScriptedCanceller();
    private final UserOperationLock userOperationLock = new UserOperationLock();
    private final CancelReservationProcessUseCaseImpl useCase =
            new CancelReservationProcessUseCaseImpl(repository, canceller, new MutableClock(NOW), userOperationLock);

    @Test
    void aConfirmedReservationIsCancelledInTheCatedraWithTheReasonAndTheDateItAnswers() {
        repository.add(process(ReservationStatus.CONFIRMED, USER));

        ReservationProcess result = cancel(REASON);

        assertThat(canceller.calls).containsExactly(PROCESS + " " + REASON);
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(stored.getCancelledAt()).isEqualTo(CANCELLED_AT);
        assertThat(stored.getCancellationReason()).isEqualTo(REASON);
        assertThat(stored.getReservationId()).isEqualTo(8251L);
        assertThat(stored.isNeedsReconciliation()).isFalse();
    }

    @Test
    void cancellingAConfirmedReservationTurnsOffAPreviousMark() {
        ReservationProcess confirmed = process(ReservationStatus.CONFIRMED, USER);
        confirmed.markForReconciliation();
        repository.add(confirmed);

        cancel(null);

        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void aProcessWaitingForTheUserIsAbandonedWithoutCallingTheCatedra(ReservationStatus status) {
        repository.add(process(status, USER));

        ReservationProcess result = cancel(REASON);

        assertThat(canceller.calls).isEmpty();
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(stored.getCancelledAt()).isEqualTo(NOW);
        assertThat(stored.getCancellationReason()).isEqualTo(REASON);
        assertThat(stored.getConfirmedAt()).isNull();
    }

    @Test
    void anOverdueProcessIsAbandonedToo() {
        ReservationProcess overdue = process(ReservationStatus.HELD, USER);
        overdue.setExpiresAt(NOW.minusSeconds(3600));
        repository.add(overdue);

        assertThat(cancel(null).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(canceller.calls).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void aMarkedProcessIsNotAbandoned(ReservationStatus status) {
        ReservationProcess marked = process(status, USER);
        marked.markForReconciliation();
        repository.add(marked);

        assertThatThrownBy(() -> cancel(null)).isInstanceOfSatisfying(ReservationInvalidProcessStateException.class,
                e -> assertThat(e.getCurrentStatus()).isEqualTo(status));

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(status);
        assertThat(repository.history(PROCESS)).isEmpty();
        assertThat(canceller.calls).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"PHONE_SUBMITTED", "EXPIRED", "INVALID"})
    void anyOtherStatusIsNotCancellable(ReservationStatus status) {
        repository.add(process(status, USER));

        assertThatThrownBy(() -> cancel(null)).isInstanceOfSatisfying(ReservationInvalidProcessStateException.class,
                e -> assertThat(e.getCurrentStatus()).isEqualTo(status));

        assertThat(repository.history(PROCESS)).isEmpty();
        assertThat(canceller.calls).isEmpty();
    }

    @Test
    void aCancelledProcessIsReturnedAsItIs() {
        ReservationProcess cancelled = process(ReservationStatus.CANCELLED, USER);
        cancelled.setCancelledAt(CANCELLED_AT);
        repository.add(cancelled);

        ReservationProcess result = cancel(REASON);

        assertThat(result.getCancelledAt()).isEqualTo(CANCELLED_AT);
        assertThat(result.getCancellationReason()).isNull();
        assertThat(repository.history(PROCESS)).isEmpty();
        assertThat(canceller.calls).isEmpty();
    }

    @Test
    void anUnknownProcessIsNotFound() {
        assertThatThrownBy(() -> cancel(null)).isInstanceOf(ReservationProcessNotFoundException.class);
    }

    @Test
    void aProcessOfAnotherUserIsNotFoundAndDoesNotChange() {
        repository.add(process(ReservationStatus.HELD, OTHER_USER));

        assertThatThrownBy(() -> cancel(null)).isInstanceOf(ReservationProcessNotFoundException.class);

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void whenTheCatedraDoesNotKnowItTheReservationStaysConfirmedAndMarked() {
        expectMarkedAfter(ReservationAppointmentNotFoundException.class, new ReservationAppointmentNotFoundException(PROCESS));
    }

    @Test
    void whenTheCatedraDoesNotAllowItTheReservationStaysConfirmedAndMarked() {
        expectMarkedAfter(ReservationNotCancellableException.class, new ReservationNotCancellableException(PROCESS));
    }

    @Test
    void whenTheCatedraDoesNotAnswerTheReservationStaysConfirmedAndMarked() {
        expectMarkedAfter(ReservationCentralUnavailableException.class, new ReservationCentralUnavailableException("down"));
    }

    @Test
    void anUnexpectedAnswerLeavesTheReservationConfirmedAndMarked() {
        expectMarkedAfter(ReservationIntegrationException.class, new ReservationIntegrationException("unreadable"));
    }

    @Test
    void aConfigurationErrorChangesNothing() {
        expectUnmarkedAfter(ReservationServiceDegradedException.class, new ReservationServiceDegradedException("missing"));
    }

    @Test
    void aReservationOfAnotherAccountChangesNothing() {
        expectUnmarkedAfter(ReservationAppointmentOwnerMismatchException.class, new ReservationAppointmentOwnerMismatchException(PROCESS));
    }

    @Test
    void whenItCannotBeMarkedTheProblemReportedIsStillTheCatedra(CapturedOutput output) {
        ReservationProcess confirmed = process(ReservationStatus.CONFIRMED, USER);
        confirmed.setPhoneNumber(PHONE);
        repository.add(confirmed);
        repository.failUpdatesWith(new IllegalStateException("could not write the row with " + PHONE));
        canceller.answer = id -> {
            throw new ReservationCentralUnavailableException("down");
        };

        assertThatThrownBy(() -> cancel(null)).isInstanceOf(ReservationCentralUnavailableException.class);

        assertThat(output).contains("could not be marked for reconciliation: IllegalStateException");
        assertThat(output).doesNotContain(PHONE);
    }

    @Test
    void whenTheCatedraCancelledAndItCannotBeSavedItIsOutOfSyncAndMarked(CapturedOutput output) {
        ReservationProcess confirmed = process(ReservationStatus.CONFIRMED, USER);
        confirmed.setPhoneNumber(PHONE);
        repository.add(confirmed);
        repository.failUpdatesWith(new IllegalStateException("could not write the row with " + PHONE));

        assertThatThrownBy(() -> cancel(null)).isInstanceOf(ReservationLocalStateOutOfSyncException.class);

        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(stored.isNeedsReconciliation()).isTrue();
        assertThat(output).contains("ERROR").contains("Catedra cancelled reservation process " + PROCESS)
                .contains("IllegalStateException");
        assertThat(output).doesNotContain(PHONE);
    }

    @Test
    void whenItCannotBeSavedOrMarkedItIsStillOutOfSync(CapturedOutput output) {
        repository.add(process(ReservationStatus.CONFIRMED, USER));
        repository.failUpdatesWhen(process -> true);
        repository.failMarks();

        assertThatThrownBy(() -> cancel(null)).isInstanceOf(ReservationLocalStateOutOfSyncException.class);

        assertThat(output).contains("could not be marked for reconciliation either");
    }

    @Test
    void whenAnAbandonCannotBeSavedNothingChanges(CapturedOutput output) {
        ReservationProcess waiting = process(ReservationStatus.WAITING_FOR_PHONE, USER);
        waiting.setPhoneNumber(PHONE);
        repository.add(waiting);
        repository.failUpdatesWith(new IllegalStateException("could not write the row with " + PHONE));

        assertThatThrownBy(() -> cancel(null)).isInstanceOf(ReservationNotSavedException.class);

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(canceller.calls).isEmpty();
        assertThat(output).contains("Abandoned reservation process " + PROCESS + " could not be saved: IllegalStateException");
        assertThat(output).doesNotContain(PHONE);
    }

    @Test
    void itWaitsForAnotherOperationOfTheUser() throws InterruptedException {
        repository.add(process(ReservationStatus.HELD, USER));
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> userOperationLock.exclusivelyFor(USER, () -> {
            taken.countDown();
            await(release);
            return null;
        }));
        assertThat(taken.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch finished = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            cancel(null);
            finished.countDown();
        });

        assertThat(finished.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.HELD);

        release.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void anotherOperationOfTheUserWaitsWhileTheCatedraAnswers() throws InterruptedException {
        repository.add(process(ReservationStatus.CONFIRMED, USER));
        CountDownLatch asking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        canceller.answer = id -> {
            asking.countDown();
            await(release);
            return CANCELLED_AT;
        };
        Thread.ofVirtual().start(() -> cancel(null));
        assertThat(asking.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch otherFinished = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> userOperationLock.exclusivelyFor(USER, () -> {
            otherFinished.countDown();
            return null;
        }));

        assertThat(otherFinished.await(300, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
        assertThat(otherFinished.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void twoSimultaneousCancellationsAskTheCatedraOnlyOnce() throws InterruptedException {
        repository.add(process(ReservationStatus.CONFIRMED, USER));
        List<ReservationStatus> results = new CopyOnWriteArrayList<>();

        Thread first = Thread.ofVirtual().start(() -> results.add(cancel(null).getStatus()));
        Thread second = Thread.ofVirtual().start(() -> results.add(cancel(null).getStatus()));
        first.join(java.time.Duration.ofSeconds(5));
        second.join(java.time.Duration.ofSeconds(5));

        assertThat(results).containsExactly(ReservationStatus.CANCELLED, ReservationStatus.CANCELLED);
        assertThat(canceller.calls).hasSize(1);
    }

    private ReservationProcess cancel(String reason) {
        return useCase.cancelReservationProcess(USER, PROCESS, reason);
    }

    private void expectMarkedAfter(Class<? extends ReservationException> type, ReservationException failure) {
        repository.add(process(ReservationStatus.CONFIRMED, USER));
        canceller.answer = id -> {
            throw failure;
        };

        assertThatThrownBy(() -> cancel(null)).isInstanceOf(type);

        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(stored.isNeedsReconciliation()).isTrue();
    }

    private void expectUnmarkedAfter(Class<? extends ReservationException> type, ReservationException failure) {
        repository.add(process(ReservationStatus.CONFIRMED, USER));
        canceller.answer = id -> {
            throw failure;
        };

        assertThatThrownBy(() -> cancel(null)).isInstanceOf(type);

        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(stored.isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    private static ReservationProcess process(ReservationStatus status, Long owner) {
        boolean confirmed = status == ReservationStatus.CONFIRMED;
        return ReservationProcess.builder().reservationProcessId(PROCESS).holdId("hold-1").userId(owner).status(status)
                .expiresAt(NOW.plusSeconds(600)).reservationId(confirmed ? 8251L : null).build();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ScriptedCanceller implements AppointmentCanceller {

        private final List<String> calls = new CopyOnWriteArrayList<>();
        private volatile Function<String, Instant> answer = id -> CANCELLED_AT;

        @Override
        public Instant cancel(String reservationProcessId, String reason) {
            calls.add(reservationProcessId + " " + reason);
            return answer.apply(reservationProcessId);
        }
    }
}
