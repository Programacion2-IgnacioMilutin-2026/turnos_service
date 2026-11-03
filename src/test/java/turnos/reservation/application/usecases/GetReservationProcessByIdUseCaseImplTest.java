package turnos.reservation.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.MutableClock;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class GetReservationProcessByIdUseCaseImplTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-11-03T13:00:00Z");
    private static final Duration MARGIN = Duration.ofSeconds(30);
    private static final Instant INSIDE_THE_MARGIN = EXPIRES_AT.plus(MARGIN);
    private static final Instant OVERDUE = INSIDE_THE_MARGIN.plusSeconds(1);
    private static final Long USER = 42L;
    private static final Long OTHER_USER = 7L;
    private static final String PROCESS = "process-1";
    private static final String PHONE = "+5492615551234";

    private final MutableClock clock = new MutableClock(EXPIRES_AT);
    private final CopyingReservationProcessRepository repository = new CopyingReservationProcessRepository();
    private final UserOperationLock userOperationLock = new UserOperationLock();
    private final GetReservationProcessByIdUseCaseImpl useCase = useCaseWith(repository);

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void anOpenProcessThatHasNotExpiredIsReturnedWithoutWriting(ReservationStatus status) {
        repository.add(process(status, USER));

        ReservationProcess result = useCase.getReservationProcessById(USER, PROCESS).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(status);
        assertThat(result.isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void anUnknownProcessIsEmpty() {
        assertThat(useCase.getReservationProcessById(USER, "does-not-exist")).isEmpty();
    }

    @Test
    void aProcessOfAnotherUserIsEmptyAndDoesNotChangeEvenIfItIsOverdue() {
        repository.add(process(ReservationStatus.WAITING_FOR_PHONE, OTHER_USER));
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));

        assertThat(useCase.getReservationProcessById(USER, PROCESS)).isEmpty();

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void anOverdueProcessWaitingForTheUserIsClosedBeforeResponding(ReservationStatus status) {
        ReservationProcess process = process(status, USER);
        process.setRejectionReason("INVALID_PHONE_NUMBER");
        process.markForReconciliation();
        repository.add(process);
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));

        ReservationProcess result = useCase.getReservationProcessById(USER, PROCESS).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(stored.getRejectionReason()).isNull();
        assertThat(stored.isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).containsExactly(ReservationStatus.EXPIRED);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void insideTheMarginNothingChanges(ReservationStatus status) {
        repository.add(process(status, USER));
        clock.advance(MARGIN);

        ReservationProcess result = useCase.getReservationProcessById(USER, PROCESS).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(status);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void anOverdueProcessWithThePhoneSubmittedIsMarkedAndStaysOpen() {
        repository.add(process(ReservationStatus.PHONE_SUBMITTED, USER));
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));

        ReservationProcess result = useCase.getReservationProcessById(USER, PROCESS).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(stored.isNeedsReconciliation()).isTrue();
        assertThat(repository.history(PROCESS)).containsExactly(ReservationStatus.PHONE_SUBMITTED);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CANCELLED", "EXPIRED", "INVALID"})
    void aClosedProcessIsReturnedAsItIsNoMatterHowOldItIs(ReservationStatus status) {
        ReservationProcess process = process(status, USER);
        process.setNeedsReconciliation(status == ReservationStatus.INVALID);
        repository.add(process);
        clock.advance(Duration.ofDays(30));

        ReservationProcess result = useCase.getReservationProcessById(USER, PROCESS).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(status);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isEqualTo(status == ReservationStatus.INVALID);
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aProcessThatIsNotOverdueRespondsWhileAnotherOperationHoldsTheLock() {
        repository.add(process(ReservationStatus.WAITING_FOR_PHONE, USER));

        assertThat(respondsWhileTheLockIsTaken()).isTrue();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aProcessAlreadyMarkedRespondsWhileAnotherOperationHoldsTheLockAndIsNotWrittenAgain() {
        ReservationProcess process = process(ReservationStatus.PHONE_SUBMITTED, USER);
        process.markForReconciliation();
        repository.add(process);
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));

        assertThat(respondsWhileTheLockIsTaken()).isTrue();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void anOverdueProcessWaitsForTheOperationThatHoldsTheLock() throws InterruptedException {
        repository.add(process(ReservationStatus.WAITING_FOR_PHONE, USER));
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> userOperationLock.exclusivelyFor(USER, () -> {
            taken.countDown();
            await(release);
            return null;
        }));
        assertThat(taken.await(5, TimeUnit.SECONDS)).isTrue();

        List<ReservationProcess> results = new CopyOnWriteArrayList<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            results.add(useCase.getReservationProcessById(USER, PROCESS).orElseThrow());
            finished.countDown();
        });

        assertThat(finished.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();

        release.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(results.getFirst().getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void aReservationConfirmedAfterTheFirstReadIsNotOverwritten() {
        repository.add(process(ReservationStatus.WAITING_FOR_PHONE, USER));
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));
        repository.afterNextOwnerRead(() -> {
            ReservationProcess confirmed = process(ReservationStatus.CONFIRMED, USER);
            confirmed.setReservationId(8251L);
            repository.add(confirmed);
        });

        ReservationProcess result = useCase.getReservationProcessById(USER, PROCESS).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(result.getReservationId()).isEqualTo(8251L);
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(stored.isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aProcessClosedAfterTheFirstReadIsNotMarked() {
        repository.add(process(ReservationStatus.PHONE_SUBMITTED, USER));
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));
        repository.afterNextOwnerRead(() -> repository.add(process(ReservationStatus.CANCELLED, USER)));

        ReservationProcess result = useCase.getReservationProcessById(USER, PROCESS).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aRejectionWithANewDeadlineAfterTheFirstReadIsKept() {
        repository.add(process(ReservationStatus.PHONE_SUBMITTED, USER));
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));
        repository.afterNextOwnerRead(() -> {
            ReservationProcess rejected = process(ReservationStatus.WAITING_FOR_PHONE, USER);
            rejected.setExpiresAt(OVERDUE.plusSeconds(600));
            rejected.setRejectionReason("INVALID_PHONE_NUMBER");
            repository.add(rejected);
        });

        ReservationProcess result = useCase.getReservationProcessById(USER, PROCESS).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(result.getRejectionReason()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void twoSimultaneousQueriesOfAnOverdueProcessWriteOnlyOnce() throws InterruptedException {
        repository.add(process(ReservationStatus.WAITING_FOR_PHONE, USER));
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));
        List<ReservationStatus> results = new CopyOnWriteArrayList<>();

        Thread first = Thread.ofVirtual().start(() -> results.add(query()));
        Thread second = Thread.ofVirtual().start(() -> results.add(query()));
        first.join(Duration.ofSeconds(5));
        second.join(Duration.ofSeconds(5));

        assertThat(results).containsExactly(ReservationStatus.EXPIRED, ReservationStatus.EXPIRED);
        assertThat(repository.history(PROCESS)).containsExactly(ReservationStatus.EXPIRED);
    }

    @Test
    void whenClosingFailsTheProcessStaysAsItWasAndTheNextQueryClosesIt() {
        repository.add(process(ReservationStatus.WAITING_FOR_PHONE, USER));
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));
        repository.failUpdatesWhen(process -> true);

        assertThatThrownBy(() -> useCase.getReservationProcessById(USER, PROCESS))
                .isInstanceOf(ReservationNotSavedException.class);
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);

        repository.failUpdatesWhen(process -> false);
        assertThat(query()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void whenMarkingFailsTheProcessStaysAsItWasAndTheNextQueryMarksIt() {
        repository.add(process(ReservationStatus.PHONE_SUBMITTED, USER));
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));
        repository.failUpdatesWhen(process -> true);

        assertThatThrownBy(() -> useCase.getReservationProcessById(USER, PROCESS))
                .isInstanceOf(ReservationNotSavedException.class);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();

        repository.failUpdatesWhen(process -> false);
        assertThat(query()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isTrue();
    }

    @Test
    void thePhoneNeverReachesTheLogWhenSavingFails(CapturedOutput output) {
        ReservationProcess process = process(ReservationStatus.PHONE_SUBMITTED, USER);
        process.setPhoneNumber(PHONE);
        repository.add(process);
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));
        repository.failUpdatesWith(new IllegalStateException("could not write the row with " + PHONE));

        assertThatThrownBy(() -> useCase.getReservationProcessById(USER, PROCESS))
                .isInstanceOf(ReservationNotSavedException.class);

        assertThat(output).contains("Overdue reservation process " + PROCESS + " could not be saved: IllegalStateException");
        assertThat(output).doesNotContain(PHONE);
    }

    @Test
    void thePhoneNeverReachesTheLogWhenSavingWorks(CapturedOutput output) {
        ReservationProcess process = process(ReservationStatus.PHONE_SUBMITTED, USER);
        process.setPhoneNumber(PHONE);
        repository.add(process);
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));

        query();

        assertThat(output).contains("Overdue reservation process " + PROCESS + " resolved");
        assertThat(output).doesNotContain(PHONE);
    }

    @Test
    void aProcessThatDisappearsWhileSavingCountsAsNotSaved() {
        ReservationProcess overdue = process(ReservationStatus.WAITING_FOR_PHONE, USER);
        clock.advance(Duration.between(EXPIRES_AT, OVERDUE));
        ReservationProcessRepository mocked = mock(ReservationProcessRepository.class);
        when(mocked.findByIdAndUserId(PROCESS, USER)).thenReturn(Optional.of(overdue));
        when(mocked.update(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCaseWith(mocked).getReservationProcessById(USER, PROCESS))
                .isInstanceOf(ReservationNotSavedException.class);
    }

    @Test
    void aFailedReadReachesTheCallerAsItIs() {
        IllegalStateException failure = new IllegalStateException("database down");
        ReservationProcessRepository mocked = mock(ReservationProcessRepository.class);
        when(mocked.findByIdAndUserId(PROCESS, USER)).thenThrow(failure);

        assertThatThrownBy(() -> useCaseWith(mocked).getReservationProcessById(USER, PROCESS)).isSameAs(failure);
    }

    private GetReservationProcessByIdUseCaseImpl useCaseWith(ReservationProcessRepository processRepository) {
        return new GetReservationProcessByIdUseCaseImpl(processRepository,
                new ReservationPolicy(ZoneId.of("America/Argentina/Mendoza"), MARGIN), clock, userOperationLock);
    }

    private ReservationStatus query() {
        return useCase.getReservationProcessById(USER, PROCESS).orElseThrow().getStatus();
    }

    // Otro hilo tiene el candado del usuario mientras se consulta: si la consulta lo pidiera, no terminaría
    private boolean respondsWhileTheLockIsTaken() {
        CountDownLatch finished = new CountDownLatch(1);
        return userOperationLock.exclusivelyFor(USER, () -> {
            Thread.ofVirtual().start(() -> {
                query();
                finished.countDown();
            });
            try {
                return finished.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ReservationProcess process(ReservationStatus status, Long owner) {
        return ReservationProcess.builder().reservationProcessId(PROCESS).holdId("hold-1").userId(owner).status(status)
                .expiresAt(EXPIRES_AT).build();
    }
}
