package turnos.reservation.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.MutableClock;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationHoldExpiredException;
import turnos.reservation.application.exception.ReservationInvalidPhoneNumberException;
import turnos.reservation.application.exception.ReservationInvalidProcessStateException;
import turnos.reservation.application.exception.ReservationLocalStateOutOfSyncException;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.exception.ReservationPhoneRequestNotReadyException;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationResult;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.PhoneSubmitter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class SubmitPhoneUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-11-02T13:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(600);
    private static final Long USER = 42L;
    private static final Long OTHER_USER = 7L;
    private static final String PROCESS = "process-1";
    private static final String HOLD = "hold-1";
    private static final String PHONE = "+54 9 261 555-1234";
    private static final String NORMALIZED = "+5492615551234";

    private final MutableClock clock = new MutableClock(NOW);
    private final CopyingReservationProcessRepository repository = new CopyingReservationProcessRepository();
    private final ScriptedSubmitter submitter = new ScriptedSubmitter();
    private final UserOperationLock userOperationLock = new UserOperationLock();
    private final SubmitPhoneUseCaseImpl useCase = new SubmitPhoneUseCaseImpl(repository, submitter,
            new ReservationPolicy(ZoneId.of("America/Argentina/Mendoza"), Duration.ofSeconds(30)), clock, userOperationLock);

    @Test
    void aValidPhoneIsSavedAndSubmittedAndTheProcessIsReturned() {
        repository.add(waiting(PROCESS, USER));

        ReservationProcess result = submit();

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(result.getPhoneNumber()).isEqualTo(NORMALIZED);
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(stored.getPhoneNumber()).isEqualTo(NORMALIZED);
        assertThat(stored.isNeedsReconciliation()).isFalse();
        assertThat(submitter.submitted).containsExactly(NORMALIZED);
    }

    @Test
    void itIsSavedBeforeSubmittingSoAResultCanNeverGetAhead() {
        repository.add(waiting(PROCESS, USER));

        submit();

        assertThat(submitter.statusInTheDatabaseWhenSubmitting).containsExactly(ReservationStatus.PHONE_SUBMITTED);
    }

    @Test
    void aPreviousRejectionIsCleared() {
        ReservationProcess process = waiting(PROCESS, USER);
        process.setRejectionReason("INVALID_PHONE_NUMBER");
        repository.add(process);

        submit();

        assertThat(repository.stored(PROCESS).getRejectionReason()).isNull();
    }

    @Test
    void anInvalidPhoneIsRejectedWithoutTouchingTheDatabaseOrTheLock() {
        CountDownLatch finished = new CountDownLatch(1);
        // El proceso ni siquiera existe, y el candado de ese usuario está tomado: igual responde enseguida
        userOperationLock.exclusivelyFor(USER, () -> {
            Thread.ofVirtual().start(() -> {
                attempt(() -> useCase.submitPhone(USER, "does-not-exist", "abc"));
                finished.countDown();
            });
            await(finished);
            return null;
        });

        assertThat(finished.getCount()).isZero();
        assertThatThrownBy(() -> useCase.submitPhone(USER, "does-not-exist", "abc"))
                .isInstanceOf(ReservationInvalidPhoneNumberException.class);
        assertThat(submitter.checks).isZero();
    }

    @Test
    void aProcessThatDoesNotExistIsNotFound() {
        assertThatThrownBy(this::submit).isInstanceOf(ReservationProcessNotFoundException.class);
        assertThat(submitter.submitted).isEmpty();
    }

    @Test
    void aProcessOfAnotherUserIsNotFoundEither() {
        repository.add(waiting(PROCESS, OTHER_USER));

        assertThatThrownBy(this::submit).isInstanceOf(ReservationProcessNotFoundException.class);
        assertThat(submitter.submitted).isEmpty();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "WAITING_FOR_PHONE")
    void onlyAProcessWaitingForThePhoneReceivesItAndTheCurrentStatusIsReported(ReservationStatus status) {
        ReservationProcess process = waiting(PROCESS, USER);
        process.setStatus(status);
        repository.add(process);

        assertThatThrownBy(this::submit).isInstanceOfSatisfying(ReservationInvalidProcessStateException.class,
                e -> assertThat(e.getCurrentStatus()).isEqualTo(status));
        assertThat(submitter.submitted).isEmpty();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aSecondSubmissionFindsThePhoneAlreadySubmitted() {
        repository.add(waiting(PROCESS, USER));
        submit();

        assertThatThrownBy(this::submit).isInstanceOfSatisfying(ReservationInvalidProcessStateException.class,
                e -> assertThat(e.getCurrentStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED));
        assertThat(submitter.submitted).hasSize(1);
    }

    @Test
    void pastTheMarginTheProcessExpiresAndNothingIsSubmitted() {
        repository.add(waiting(PROCESS, USER));
        clock.advance(Duration.between(NOW, EXPIRES_AT).plusSeconds(31));

        assertThatThrownBy(this::submit).isInstanceOf(ReservationHoldExpiredException.class);

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(submitter.submitted).isEmpty();
    }

    @Test
    void withinTheMarginItIsSubmitted() {
        repository.add(waiting(PROCESS, USER));
        clock.advance(Duration.between(NOW, EXPIRES_AT).plusSeconds(30));

        assertThat(submit().getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
    }

    @Test
    void withoutThePhoneRequestItIsNotReadyAndNothingChanges() {
        ReservationProcess process = waiting(PROCESS, USER);
        process.setRequestEventId(null);
        repository.add(process);

        assertThatThrownBy(this::submit).isInstanceOf(ReservationPhoneRequestNotReadyException.class);

        assertThat(repository.history(PROCESS)).isEmpty();
        assertThat(submitter.checks).isZero();
    }

    @Test
    void withoutAnIntegrationNothingIsSavedSubmittedOrMarked() {
        repository.add(waiting(PROCESS, USER));
        submitter.available = false;

        assertThatThrownBy(this::submit).isInstanceOf(ReservationCentralUnavailableException.class);

        assertThat(repository.history(PROCESS)).isEmpty();
        assertThat(submitter.submitted).isEmpty();
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(stored.isNeedsReconciliation()).isFalse();
        assertThat(stored.getPhoneNumber()).isNull();
    }

    @Test
    void whenThePhoneCannotBePublishedTheProcessGoesBackMarkedAndTheFailureIsReported() {
        ReservationProcess process = waiting(PROCESS, USER);
        process.setRejectionReason("INVALID_PHONE_NUMBER");
        repository.add(process);
        submitter.failure = new ReservationCentralUnavailableException("broker down");

        assertThatThrownBy(this::submit).isInstanceOf(ReservationCentralUnavailableException.class);

        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(repository.history(PROCESS)).containsExactly(ReservationStatus.PHONE_SUBMITTED, ReservationStatus.WAITING_FOR_PHONE);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(stored.isNeedsReconciliation()).isTrue();
        assertThat(stored.getRejectionReason()).isNull();
        assertThat(stored.getPhoneNumber()).isEqualTo(NORMALIZED);
    }

    @Test
    void afterAFailedPublicationTheUserCanSubmitAgainWithTheSamePhoneRequest() {
        repository.add(waiting(PROCESS, USER));
        submitter.failure = new ReservationCentralUnavailableException("broker down");
        assertThatThrownBy(this::submit).isInstanceOf(ReservationCentralUnavailableException.class);
        submitter.failure = null;

        ReservationProcess result = useCase.submitPhone(USER, PROCESS, "2615550000");

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(result.getRequestEventId()).isEqualTo("request-event-1");
        assertThat(submitter.statusInTheDatabaseWhenSubmitting).hasSize(2);
        assertThat(submitter.submitted).containsExactly("2615550000");
    }

    @Test
    void anUnexpectedFailureWhenPublishingAlsoGoesBack() {
        repository.add(waiting(PROCESS, USER));
        submitter.failure = new IllegalArgumentException("bug");

        assertThatThrownBy(this::submit).isInstanceOf(IllegalArgumentException.class);

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
    }

    @Test
    void whenTheFirstSaveFailsNothingIsPublishedAndTheProcessStaysAsItWas(CapturedOutput output) {
        ReservationProcess process = waiting(PROCESS, USER);
        process.setRejectionReason("INVALID_PHONE_NUMBER");
        repository.add(process);
        repository.failUpdatesWhen(saved -> saved.getStatus() == ReservationStatus.PHONE_SUBMITTED);

        assertThatThrownBy(this::submit).isInstanceOf(ReservationNotSavedException.class);

        assertThat(submitter.submitted).isEmpty();
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(stored.getRejectionReason()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(stored.getPhoneNumber()).isNull();
        assertThat(output.getAll()).contains("ERROR").contains("nothing was published").doesNotContain("5492615551234");
    }

    @Test
    void whenItCannotGoBackTheStateIsOutOfSyncAndItIsASevereErrorInTheLog(CapturedOutput output) {
        repository.add(waiting(PROCESS, USER));
        repository.failUpdatesWhen(saved -> saved.getStatus() == ReservationStatus.WAITING_FOR_PHONE);
        submitter.failure = new ReservationCentralUnavailableException("broker down");

        assertThatThrownBy(this::submit).isInstanceOf(ReservationLocalStateOutOfSyncException.class);

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
        assertThat(output.getAll()).contains("ERROR").contains("Reservation process process-1 (hold hold-1) stays as PHONE_SUBMITTED")
                .doesNotContain("5492615551234");
    }

    @Test
    void thePhoneNeverReachesTheLog(CapturedOutput output) {
        repository.add(waiting(PROCESS, USER));
        submit();
        repository.add(waiting("process-2", OTHER_USER));
        submitter.failure = new ReservationCentralUnavailableException("broker down");
        assertThatThrownBy(() -> useCase.submitPhone(OTHER_USER, "process-2", PHONE))
                .isInstanceOf(ReservationCentralUnavailableException.class);

        assertThat(output.getAll()).doesNotContain("5492615551234").doesNotContain("261 555");
    }

    @Test
    void twoSubmissionsOfTheSameUserAreAttendedOneAtATimeAndOnlyOnePublishes() throws InterruptedException {
        repository.add(waiting(PROCESS, USER));
        submitter.beforeAnswering = () -> sleep(200);
        List<Object> answers = new CopyOnWriteArrayList<>();

        Thread first = Thread.ofVirtual().start(() -> answers.add(attempt(this::submit)));
        Thread second = Thread.ofVirtual().start(() -> answers.add(attempt(this::submit)));
        first.join(10000);
        second.join(10000);

        assertThat(answers).hasSize(2);
        assertThat(answers).filteredOn(ReservationProcess.class::isInstance).hasSize(1);
        assertThat(answers).filteredOn(ReservationInvalidProcessStateException.class::isInstance).hasSize(1);
        assertThat(submitter.submitted).hasSize(1);
    }

    @Test
    void aSubmissionOfAnotherUserDoesNotWait() throws InterruptedException {
        repository.add(waiting(PROCESS, USER));
        repository.add(waiting("process-2", OTHER_USER));
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        submitter.beforeAnswering = () -> {
            if (publishing.getCount() > 0) {
                publishing.countDown();
                await(release);
            }
        };

        Thread first = Thread.ofVirtual().start(() -> attempt(this::submit));
        assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();

        ReservationProcess other = useCase.submitPhone(OTHER_USER, "process-2", PHONE);

        assertThat(other.getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        release.countDown();
        first.join(10000);
    }

    @Test
    void aResultThatArrivesWhilePublishingIsAppliedAfterwardsAndNothingIsOverwritten() throws InterruptedException {
        repository.add(waiting(PROCESS, USER));
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        submitter.beforeAnswering = () -> {
            publishing.countDown();
            await(release);
        };
        ProcessReservationResultUseCaseImpl results = results();
        Instant rejectionExpiresAt = EXPIRES_AT.plusSeconds(60);

        Thread submission = Thread.ofVirtual().start(() -> attempt(this::submit));
        assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
        CountDownLatch resultFinished = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            results.processReservationResult(new ReservationResult.Rejected("event-1", PROCESS, HOLD, "INVALID_PHONE_NUMBER", rejectionExpiresAt));
            resultFinished.countDown();
        });
        assertThat(resultFinished.await(300, TimeUnit.MILLISECONDS)).isFalse();

        release.countDown();
        submission.join(10000);
        assertThat(resultFinished.await(5, TimeUnit.SECONDS)).isTrue();

        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(repository.history(PROCESS)).containsExactly(ReservationStatus.PHONE_SUBMITTED, ReservationStatus.WAITING_FOR_PHONE);
        assertThat(stored.getRejectionReason()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(stored.getExpiresAt()).isEqualTo(rejectionExpiresAt);
        assertThat(stored.getPhoneNumber()).isEqualTo(NORMALIZED);
    }

    @Test
    void aConfirmationThatArrivesWhileAFailingPublicationGoesBackIsNotLost() throws InterruptedException {
        repository.add(waiting(PROCESS, USER));
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        submitter.beforeAnswering = () -> {
            publishing.countDown();
            await(release);
        };
        submitter.failure = new ReservationCentralUnavailableException("timeout");
        ProcessReservationResultUseCaseImpl results = results();

        Thread submission = Thread.ofVirtual().start(() -> attempt(this::submit));
        assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
        Thread result = Thread.ofVirtual().start(() -> results.processReservationResult(new ReservationResult.Confirmed(
                "event-1", PROCESS, HOLD, 8251L, NOW, null, null, null)));
        sleep(200);
        release.countDown();
        submission.join(10000);
        result.join(10000);

        assertThat(repository.history(PROCESS)).containsExactly(ReservationStatus.PHONE_SUBMITTED,
                ReservationStatus.WAITING_FOR_PHONE, ReservationStatus.CONFIRMED);
        assertThat(repository.stored(PROCESS).getReservationId()).isEqualTo(8251L);
    }

    private ProcessReservationResultUseCaseImpl results() {
        return new ProcessReservationResultUseCaseImpl(repository, new InMemoryProcessedEventRepository(), Runnable::run,
                userOperationLock, clock);
    }

    private ReservationProcess submit() {
        return useCase.submitPhone(USER, PROCESS, PHONE);
    }

    private static Object attempt(Supplier<Object> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static ReservationProcess waiting(String reservationProcessId, Long userId) {
        return ReservationProcess.builder()
                .reservationProcessId(reservationProcessId)
                .holdId(HOLD)
                .userId(userId)
                .professionalId(201L)
                .date(LocalDate.of(2026, 11, 9))
                .startTime(LocalTime.of(10, 30))
                .status(ReservationStatus.WAITING_FOR_PHONE)
                .expiresAt(EXPIRES_AT)
                .requestEventId("request-event-1")
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Publica lo que le piden o falla como indique el test, y anota qué había en la base en ese momento
    private final class ScriptedSubmitter implements PhoneSubmitter {
        private final List<String> submitted = new CopyOnWriteArrayList<>();
        private final List<ReservationStatus> statusInTheDatabaseWhenSubmitting = new CopyOnWriteArrayList<>();
        private volatile boolean available = true;
        private volatile int checks;
        private volatile RuntimeException failure;
        private volatile Runnable beforeAnswering = () -> {
        };

        @Override
        public boolean canSubmit() {
            checks++;
            return available;
        }

        @Override
        public void submit(ReservationProcess reservationProcess) {
            statusInTheDatabaseWhenSubmitting.add(repository.stored(reservationProcess.getReservationProcessId()).getStatus());
            beforeAnswering.run();
            if (failure != null) {
                throw failure;
            }
            submitted.add(reservationProcess.getPhoneNumber());
        }
    }
}
