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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ExpireOverdueProcessesUseCaseImplTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-11-03T13:00:00Z");
    private static final Duration MARGIN = Duration.ofSeconds(30);
    private static final Instant INSIDE_THE_MARGIN = EXPIRES_AT.plus(MARGIN);
    private static final Instant OVERDUE = INSIDE_THE_MARGIN.plusSeconds(1);
    private static final String PROCESS = "process-1";
    private static final Long USER = 42L;
    private static final String PHONE = "+5492615551234";

    private final MutableClock clock = new MutableClock(EXPIRES_AT);
    private final CopyingReservationProcessRepository repository = new CopyingReservationProcessRepository();
    private final UserOperationLock userOperationLock = new UserOperationLock();
    private final ExpireOverdueProcessesUseCaseImpl useCase = useCaseWith(repository);

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void anOverdueProcessWaitingForTheUserIsClosed(ReservationStatus status) {
        repository.add(process(PROCESS, USER, status));
        goTo(OVERDUE);

        useCase.expireOverdueProcesses();

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(repository.history(PROCESS)).containsExactly(ReservationStatus.EXPIRED);
    }

    // Se cierra igual: un marcado puede tener su resultado en camino, pero el margen ya le dio tiempo de llegar
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    void anOverdueProcessWaitingForTheUserIsClosedEvenIfItIsMarkedAndTheMarkIsTurnedOff(ReservationStatus status) {
        ReservationProcess process = process(PROCESS, USER, status);
        process.markForReconciliation();
        repository.add(process);
        goTo(OVERDUE);

        useCase.expireOverdueProcesses();

        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(stored.isNeedsReconciliation()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void aProcessInsideTheMarginIsNotTouched(ReservationStatus status) {
        repository.add(process(PROCESS, USER, status));
        goTo(INSIDE_THE_MARGIN);

        useCase.expireOverdueProcesses();

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(status);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void anOverdueProcessWithThePhoneSubmittedIsMarkedWithoutClosingItAndKeepsItsData() {
        repository.add(withPhone(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED)));
        goTo(OVERDUE);

        useCase.expireOverdueProcesses();

        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(stored.isNeedsReconciliation()).isTrue();
        assertThat(stored.getPhoneNumber()).isEqualTo(PHONE);
        assertThat(stored.getExpiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void aProcessWithThePhoneSubmittedIsMarkedOnlyOnce() {
        repository.add(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED));
        goTo(OVERDUE);

        useCase.expireOverdueProcesses();
        useCase.expireOverdueProcesses();

        assertThat(repository.history(PROCESS)).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CANCELLED", "EXPIRED", "INVALID"})
    void aClosedProcessIsNotTouchedNoMatterHowOldItIs(ReservationStatus status) {
        repository.add(process(PROCESS, USER, status));
        goTo(OVERDUE);

        useCase.expireOverdueProcesses();

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(status);
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aSweepWithoutOverdueProcessesLogsZero(CapturedOutput output) {
        useCase.expireOverdueProcesses();

        assertThat(output).contains("Overdue sweep of " + EXPIRES_AT + " finished: 0 closed, 0 marked for reconciliation, 0 failed");
    }

    @Test
    void aSweepLogsWhatItActuallyDid(CapturedOutput output) {
        repository.add(process("process-1", 1L, ReservationStatus.HELD));
        repository.add(process("process-2", 2L, ReservationStatus.WAITING_FOR_PHONE));
        repository.add(process("process-3", 3L, ReservationStatus.PHONE_SUBMITTED));
        repository.add(process("process-4", 4L, ReservationStatus.CONFIRMED));
        goTo(OVERDUE);

        useCase.expireOverdueProcesses();

        assertThat(output).contains("Overdue sweep of " + OVERDUE + " finished: 2 closed, 1 marked for reconciliation, 0 failed");
    }

    @Test
    void aFailureSavingOneProcessDoesNotStopTheOthersAndTheNextSweepClosesIt(CapturedOutput output) {
        repository.add(process("process-1", 1L, ReservationStatus.HELD));
        repository.add(withPhone(process("process-2", 2L, ReservationStatus.WAITING_FOR_PHONE)));
        repository.add(process("process-3", 3L, ReservationStatus.HELD));
        goTo(OVERDUE);
        repository.failUpdatesWith(new IllegalStateException("could not write the row with " + PHONE));
        repository.failUpdatesWhen(process -> process.getReservationProcessId().equals("process-2"));

        useCase.expireOverdueProcesses();

        assertThat(repository.stored("process-1").getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(repository.stored("process-2").getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(repository.stored("process-3").getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(output).contains("Overdue reservation process process-2 could not be resolved: IllegalStateException");
        assertThat(output).contains("2 closed, 0 marked for reconciliation, 1 failed");
        assertThat(output).doesNotContain(PHONE);

        repository.failUpdatesWhen(process -> false);
        useCase.expireOverdueProcesses();

        assertThat(repository.stored("process-2").getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void aFailureReadingAProcessAgainCountsAsAFailureOfThatProcessOnly(CapturedOutput output) {
        ReservationProcess process = process(PROCESS, USER, ReservationStatus.HELD);
        ReservationProcessRepository failing = mock(ReservationProcessRepository.class);
        when(failing.findOverdue(any())).thenReturn(List.of(process));
        when(failing.findById(PROCESS)).thenThrow(new ReservationNotSavedException(PROCESS, new IllegalStateException("database down")));
        goTo(OVERDUE);

        useCaseWith(failing).expireOverdueProcesses();

        verify(failing, never()).update(any(), any());
        assertThat(output).contains("Overdue reservation process " + PROCESS + " could not be resolved: ReservationNotSavedException");
        assertThat(output).contains("0 closed, 0 marked for reconciliation, 1 failed");
    }

    @Test
    void aFailureSearchingEndsTheSweepWithoutPropagatingTheError(CapturedOutput output) {
        ReservationProcessRepository failing = mock(ReservationProcessRepository.class);
        when(failing.findOverdue(any())).thenThrow(new ReservationNotSavedException(new IllegalStateException("database down")));

        useCaseWith(failing).expireOverdueProcesses();

        verify(failing, never()).update(any(), any());
        assertThat(output).contains("Overdue sweep could not search for processes: ReservationNotSavedException");
        assertThat(output).doesNotContain("finished");
    }

    @Test
    void theSearchUsesTheSafetyMarginOfThePolicy() {
        ReservationProcessRepository searching = mock(ReservationProcessRepository.class);
        when(searching.findOverdue(any())).thenReturn(List.of());
        goTo(OVERDUE);

        useCaseWith(searching).expireOverdueProcesses();

        verify(searching).findOverdue(OVERDUE.minus(MARGIN));
    }

    @Test
    void aProcessThatDisappearedAfterTheSearchIsNotAnError(CapturedOutput output) {
        ReservationProcessRepository vanishing = mock(ReservationProcessRepository.class);
        when(vanishing.findOverdue(any())).thenReturn(List.of(process(PROCESS, USER, ReservationStatus.HELD)));
        when(vanishing.findById(PROCESS)).thenReturn(Optional.empty());
        goTo(OVERDUE);

        useCaseWith(vanishing).expireOverdueProcesses();

        verify(vanishing, never()).update(any(), any());
        assertThat(output).contains("0 closed, 0 marked for reconciliation, 0 failed");
    }

    @Test
    void aReservationConfirmedAfterTheSearchIsNotOverwritten() {
        repository.add(process(PROCESS, USER, ReservationStatus.WAITING_FOR_PHONE));
        goTo(OVERDUE);
        repository.afterNextOverdueSearch(() -> {
            ReservationProcess confirmed = process(PROCESS, USER, ReservationStatus.CONFIRMED);
            confirmed.setReservationId(8251L);
            repository.add(confirmed);
        });

        useCase.expireOverdueProcesses();

        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(stored.getReservationId()).isEqualTo(8251L);
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aProcessClosedAfterTheSearchIsNotMarked() {
        repository.add(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED));
        goTo(OVERDUE);
        repository.afterNextOverdueSearch(() -> repository.add(process(PROCESS, USER, ReservationStatus.CANCELLED)));

        useCase.expireOverdueProcesses();

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aNewDeadlineAfterTheSearchKeepsTheProcessOpen() {
        repository.add(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED));
        goTo(OVERDUE);
        repository.afterNextOverdueSearch(() -> {
            ReservationProcess rejected = process(PROCESS, USER, ReservationStatus.WAITING_FOR_PHONE);
            rejected.setExpiresAt(OVERDUE.plusSeconds(600));
            rejected.setRejectionReason("INVALID_PHONE_NUMBER");
            repository.add(rejected);
        });

        useCase.expireOverdueProcesses();

        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(stored.getRejectionReason()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(stored.isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void theSweepWaitsForTheOperationThatHoldsTheLockAndRespectsWhatItLeft() throws InterruptedException {
        repository.add(process(PROCESS, USER, ReservationStatus.WAITING_FOR_PHONE));
        goTo(OVERDUE);
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> userOperationLock.exclusivelyFor(USER, () -> {
            taken.countDown();
            await(release);
            ReservationProcess confirmed = process(PROCESS, USER, ReservationStatus.CONFIRMED);
            confirmed.setReservationId(8251L);
            repository.add(confirmed);
            return null;
        }));
        assertThat(taken.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch finished = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            useCase.expireOverdueProcesses();
            finished.countDown();
        });

        assertThat(finished.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();

        release.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(repository.stored(PROCESS).getReservationId()).isEqualTo(8251L);
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aSweepClosesTheProcessOnceTheOperationThatHoldsTheLockLeavesItOverdue() throws InterruptedException {
        repository.add(process(PROCESS, USER, ReservationStatus.WAITING_FOR_PHONE));
        goTo(OVERDUE);
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
            useCase.expireOverdueProcesses();
            finished.countDown();
        });
        assertThat(finished.await(300, TimeUnit.MILLISECONDS)).isFalse();

        release.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    private ExpireOverdueProcessesUseCaseImpl useCaseWith(ReservationProcessRepository processRepository) {
        return new ExpireOverdueProcessesUseCaseImpl(processRepository,
                new ReservationPolicy(ZoneId.of("America/Argentina/Mendoza"), MARGIN), clock, userOperationLock);
    }

    private void goTo(Instant instant) {
        clock.advance(Duration.between(clock.instant(), instant));
    }

    private static ReservationProcess withPhone(ReservationProcess process) {
        process.setPhoneNumber(PHONE);
        return process;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ReservationProcess process(String reservationProcessId, Long owner, ReservationStatus status) {
        return ReservationProcess.builder().reservationProcessId(reservationProcessId).holdId("hold-" + reservationProcessId)
                .userId(owner).status(status).expiresAt(EXPIRES_AT).build();
    }
}
