package turnos.reservation.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.MutableClock;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationListCriteria;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationRecord;
import turnos.reservation.domain.model.ReservationRecordStatus;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.AppointmentFinder;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ReconcilePendingProcessesUseCaseImplTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-11-06T13:00:00Z");
    private static final Duration MARGIN = Duration.ofSeconds(30);
    private static final Instant BEFORE_EXPIRING = EXPIRES_AT.minusSeconds(60);
    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-06T12:58:00Z");
    private static final String PROCESS = "process-1";
    private static final Long USER = 42L;
    private static final String PHONE = "+5492615551234";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);
    private static final LocalTime TIME = LocalTime.of(10, 30);

    private final MutableClock clock = new MutableClock(BEFORE_EXPIRING);
    private final CopyingReservationProcessRepository repository = new CopyingReservationProcessRepository();
    private final ScriptedAppointmentFinder finder = new ScriptedAppointmentFinder();
    private final UserOperationLock userOperationLock = new UserOperationLock();
    private final ReconcilePendingProcessesUseCaseImpl useCase = useCaseWith(repository);

    // Qué procesos se consultan

    @Test
    void theFirstCycleAlsoChecksOpenProcessesWithoutTheMarkAndTheNextOnesOnlyTheMarked() {
        repository.add(process("process-1", 1L, ReservationStatus.HELD));
        repository.add(marked(process("process-2", 2L, ReservationStatus.PHONE_SUBMITTED)));
        finder.answer("process-2", record("process-2", ReservationRecordStatus.PHONE_PENDING));

        useCase.reconcilePendingProcesses();
        assertThat(finder.consulted()).containsExactly("process-1", "process-2");

        finder.clear();
        useCase.reconcilePendingProcesses();
        assertThat(finder.consulted()).containsExactly("process-2");
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CANCELLED", "EXPIRED", "INVALID"})
    void aClosedProcessWithoutTheMarkIsNeverChecked(ReservationStatus status) {
        repository.add(process(PROCESS, USER, status));

        useCase.reconcilePendingProcesses();

        assertThat(finder.consulted()).isEmpty();
    }

    @Test
    void theOwnerIsIdentifiedBeforeCatedraByItsId() {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.HELD)));

        useCase.reconcilePendingProcesses();

        assertThat(finder.patients()).containsExactly("42");
    }

    @Test
    void theReviewOfOpenProcessesIsRepeatedUntilACycleCompletesIt() {
        repository.add(process("process-1", 1L, ReservationStatus.HELD));
        repository.add(process("process-2", 2L, ReservationStatus.WAITING_FOR_PHONE));
        finder.fail("process-1", () -> new ReservationCentralUnavailableException("down"));

        useCase.reconcilePendingProcesses();
        assertThat(finder.consulted()).containsExactly("process-1");

        finder.clear();
        useCase.reconcilePendingProcesses();
        assertThat(finder.consulted()).containsExactly("process-1", "process-2");

        finder.clear();
        useCase.reconcilePendingProcesses();
        assertThat(finder.consulted()).isEmpty();
    }

    @Test
    void aFailureSearchingEndsTheCycleAndKeepsTheReviewOfOpenProcessesPending(CapturedOutput output) {
        ReservationProcessRepository failing = mock(ReservationProcessRepository.class);
        when(failing.findToReconcile(true)).thenThrow(new ReservationNotSavedException(new IllegalStateException("database down")));
        ReconcilePendingProcessesUseCaseImpl failingUseCase = useCaseWith(failing);

        failingUseCase.reconcilePendingProcesses();
        failingUseCase.reconcilePendingProcesses();

        assertThat(output).contains("Reconciliation could not search for processes: ReservationNotSavedException");
        assertThat(output).doesNotContain("finished");
        assertThat(finder.consulted()).isEmpty();
    }

    @Test
    void aMarkedProcessThatCannotBeReconciledIsNotCheckedAndCountsAsFailed(CapturedOutput output) {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.CANCELLED)));

        useCase.reconcilePendingProcesses();

        assertThat(finder.consulted()).isEmpty();
        assertThat(output).contains("Reservation process process-1 cannot be reconciled: it is CANCELLED and marked");
        assertThat(output).contains("0 corrected, 0 already matched, 0 still pending, 0 unresolved, 0 discarded, 1 failed");
    }

    // Problemas de la cátedra

    @Test
    void anUnavailableCatedraStopsTheCycleAndLeavesTheRestForTheNextOne(CapturedOutput output) {
        repository.add(marked(withExpiration(process("process-1", 1L, ReservationStatus.PHONE_SUBMITTED), EXPIRES_AT.minusSeconds(30))));
        repository.add(marked(withExpiration(process("process-2", 2L, ReservationStatus.PHONE_SUBMITTED), EXPIRES_AT.minusSeconds(20))));
        repository.add(marked(withExpiration(process("process-3", 3L, ReservationStatus.PHONE_SUBMITTED), EXPIRES_AT.minusSeconds(10))));
        finder.answer("process-1", confirmed("process-1"));
        finder.fail("process-2", () -> new ReservationCentralUnavailableException("Catedra answered HTTP 503"));

        useCase.reconcilePendingProcesses();

        assertThat(finder.consulted()).containsExactly("process-1", "process-2");
        assertThat(repository.stored("process-1").getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        for (String untouched : List.of("process-2", "process-3")) {
            assertThat(repository.stored(untouched).getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
            assertThat(repository.stored(untouched).isNeedsReconciliation()).isTrue();
        }
        assertThat(output).contains("stopped, 2 reservation processes left for the next cycle: Catedra unavailable: Catedra answered HTTP 503");
        assertThat(output).contains("1 corrected, 0 already matched, 0 still pending, 0 unresolved, 0 discarded, 0 failed, 2 left for the next cycle");

        finder.clear();
        finder.answer("process-2", confirmed("process-2"));
        finder.answer("process-3", confirmed("process-3"));
        useCase.reconcilePendingProcesses();

        assertThat(finder.consulted()).containsExactly("process-2", "process-3");
        assertThat(repository.stored("process-3").getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void aConfigurationErrorAlsoStopsTheCycle(CapturedOutput output) {
        repository.add(marked(process("process-1", 1L, ReservationStatus.HELD)));
        repository.add(marked(process("process-2", 2L, ReservationStatus.HELD)));
        finder.fail("process-1", () -> new ReservationServiceDegradedException("Catedra answered STUDENT_INTEGRATION_NOT_FOUND"));

        useCase.reconcilePendingProcesses();

        assertThat(finder.consulted()).containsExactly("process-1");
        assertThat(output).contains("stopped, 2 reservation processes left for the next cycle: Catedra integration degraded");
    }

    @Test
    void anAnswerThatCannotBeUsedSkipsOnlyThatProcess(CapturedOutput output) {
        repository.add(marked(withExpiration(process("process-1", 1L, ReservationStatus.PHONE_SUBMITTED), EXPIRES_AT.minusSeconds(20))));
        repository.add(marked(withExpiration(process("process-2", 2L, ReservationStatus.PHONE_SUBMITTED), EXPIRES_AT.minusSeconds(10))));
        repository.add(marked(process("process-3", 3L, ReservationStatus.PHONE_SUBMITTED)));
        finder.fail("process-1", () -> new ReservationIntegrationException("Catedra answered a confirmed appointment without its confirmation date"));
        finder.fail("process-2", () -> new IllegalStateException("unexpected"));
        finder.answer("process-3", confirmed("process-3"));

        useCase.reconcilePendingProcesses();

        assertThat(repository.stored("process-1").isNeedsReconciliation()).isTrue();
        assertThat(repository.stored("process-2").isNeedsReconciliation()).isTrue();
        assertThat(repository.stored("process-3").getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(output).contains("Reservation process process-1 could not be checked with Catedra: Internal integration error: "
                + "Catedra answered a confirmed appointment without its confirmation date");
        assertThat(output).contains("Reservation process process-2 could not be checked with Catedra: IllegalStateException");
        assertThat(output).contains("1 corrected, 0 already matched, 0 still pending, 0 unresolved, 0 discarded, 2 failed, 0 left");
    }

    // Qué se guarda

    @Test
    void aConfirmationOfCatedraIsSavedWithItsData(CapturedOutput output) {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED)));
        finder.answer(PROCESS, confirmed(PROCESS));

        useCase.reconcilePendingProcesses();

        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(stored.getReservationId()).isEqualTo(8251L);
        assertThat(stored.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(stored.isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).containsExactly(ReservationStatus.CONFIRMED);
        assertThat(output).contains("Reservation process process-1 reconciled with Catedra (CONFIRMED): PHONE_SUBMITTED is now CONFIRMED");
        assertThat(output).doesNotContain("different professional");
    }

    @Test
    void aConfirmationWithAnotherAppointmentKeepsTheOneOfCatedraAndWarns(CapturedOutput output) {
        repository.add(invalid(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED)));
        finder.answer(PROCESS, new ReservationRecord(PROCESS, ReservationRecordStatus.CONFIRMED, 8251L, CONFIRMED_AT, null,
                null, 15L, DAY, LocalTime.of(11, 0)));

        useCase.reconcilePendingProcesses();

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(repository.stored(PROCESS).getStartTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(output).contains("WARN").contains("Reservation process process-1: Catedra confirmed a different professional, date or time");
    }

    @Test
    void aConfirmationThatWasAlreadyHereOnlyLosesTheMarkWithoutWarning(CapturedOutput output) {
        ReservationProcess confirmed = process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED);
        confirmed.confirmReservation(8251L, CONFIRMED_AT, null, null, null);
        repository.add(marked(confirmed));
        finder.answer(PROCESS, new ReservationRecord(PROCESS, ReservationRecordStatus.CONFIRMED, 8251L, CONFIRMED_AT, null,
                null, 15L, DAY, LocalTime.of(11, 0)));

        useCase.reconcilePendingProcesses();

        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
        assertThat(repository.stored(PROCESS).getStartTime()).isEqualTo(TIME);
        assertThat(output).contains("Reservation process process-1 already matched Catedra (CONFIRMED): it stays CONFIRMED");
        assertThat(output).doesNotContain("different professional");
    }

    @Test
    void aHeldProcessWithoutRecordThatHasNotExpiredLosesTheMarkAndStaysOpen(CapturedOutput output) {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.HELD)));

        useCase.reconcilePendingProcesses();

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).containsExactly(ReservationStatus.HELD);
        assertThat(output).contains("Reservation process process-1 already matched Catedra (no record): it stays HELD");
    }

    @Test
    void anOpenProcessThatAlreadyMatchesAndWasNotMarkedIsNotSaved() {
        repository.add(process(PROCESS, USER, ReservationStatus.HELD));

        useCase.reconcilePendingProcesses();

        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aProcessWithoutRecordPastItsExpirationIsClosedWithTheSafetyMargin() {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.HELD)));
        clock.advance(Duration.between(BEFORE_EXPIRING, EXPIRES_AT.plus(MARGIN)));

        useCase.reconcilePendingProcesses();
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.HELD);

        repository.add(marked(process(PROCESS, USER, ReservationStatus.HELD)));
        clock.advance(Duration.ofSeconds(1));
        useCase.reconcilePendingProcesses();
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void aReservationWithoutPhoneGivesTheNumberToAProcessWaitingForThePhone() {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.WAITING_FOR_PHONE)));
        finder.answer(PROCESS, new ReservationRecord(PROCESS, ReservationRecordStatus.PHONE_PENDING, 1664L, null, null,
                null, null, null, null));

        useCase.reconcilePendingProcesses();

        assertThat(repository.stored(PROCESS).getReservationId()).isEqualTo(1664L);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
    }

    @Test
    void aSubmittedPhoneThatCatedraHasNotResolvedKeepsItsMarkAndIsCheckedAgain(CapturedOutput output) {
        repository.add(marked(withPhone(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED))));
        finder.answer(PROCESS, record(PROCESS, ReservationRecordStatus.PHONE_PENDING));

        useCase.reconcilePendingProcesses();
        useCase.reconcilePendingProcesses();

        // Solo la primera vez hay algo que guardar: el número de reserva que informa la cátedra
        assertThat(repository.history(PROCESS)).containsExactly(ReservationStatus.PHONE_SUBMITTED);
        assertThat(repository.stored(PROCESS).getReservationId()).isEqualTo(8251L);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isTrue();
        assertThat(finder.consulted()).containsExactly(PROCESS, PROCESS);
        assertThat(output).contains("Reservation process process-1 stays PHONE_SUBMITTED: Catedra has not resolved the submitted phone yet");
    }

    @Test
    void aClosedProcessThatCatedraContradictsIsLeftAsItIsAndLoggedAsAnError(CapturedOutput output) {
        ReservationProcess confirmed = process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED);
        confirmed.confirmReservation(8251L, CONFIRMED_AT, null, null, null);
        repository.add(marked(confirmed));

        useCase.reconcilePendingProcesses();

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
        assertThat(output).contains("ERROR").contains("Reservation process process-1 is CONFIRMED here, but Catedra has no record; "
                + "it cannot be corrected without reopening it");
    }

    @Test
    void aFailureSavingOneProcessDoesNotStopTheOthersAndTheNextCycleFixesIt(CapturedOutput output) {
        repository.add(marked(withExpiration(process("process-1", 1L, ReservationStatus.PHONE_SUBMITTED), EXPIRES_AT.minusSeconds(20))));
        repository.add(marked(withPhone(withExpiration(process("process-2", 2L, ReservationStatus.PHONE_SUBMITTED), EXPIRES_AT.minusSeconds(10)))));
        repository.add(marked(process("process-3", 3L, ReservationStatus.PHONE_SUBMITTED)));
        for (String id : List.of("process-1", "process-2", "process-3")) {
            finder.answer(id, confirmed(id));
        }
        repository.failUpdatesWith(new IllegalStateException("could not write the row with " + PHONE));
        repository.failUpdatesWhen(process -> process.getReservationProcessId().equals("process-2"));

        useCase.reconcilePendingProcesses();

        assertThat(repository.stored("process-1").getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(repository.stored("process-2").getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(repository.stored("process-2").isNeedsReconciliation()).isTrue();
        assertThat(repository.stored("process-3").getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(output).contains("Reservation process process-2 could not be reconciled: IllegalStateException");
        assertThat(output).contains("2 corrected, 0 already matched, 0 still pending, 0 unresolved, 0 discarded, 1 failed, 0 left");
        assertThat(output).doesNotContain(PHONE);

        repository.failUpdatesWhen(process -> false);
        useCase.reconcilePendingProcesses();

        assertThat(repository.stored("process-2").getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void everyProcessAndTheCycleLeaveALine(CapturedOutput output) {
        repository.add(marked(withExpiration(process("process-1", 1L, ReservationStatus.PHONE_SUBMITTED), EXPIRES_AT.minusSeconds(30))));
        repository.add(marked(withExpiration(process("process-2", 2L, ReservationStatus.WAITING_FOR_PHONE), EXPIRES_AT.minusSeconds(20))));
        repository.add(marked(withExpiration(process("process-3", 3L, ReservationStatus.PHONE_SUBMITTED), EXPIRES_AT.minusSeconds(10))));
        repository.add(marked(invalid(process("process-4", 4L, ReservationStatus.PHONE_SUBMITTED))));
        finder.answer("process-1", confirmed("process-1"));
        finder.answer("process-2", record("process-2", ReservationRecordStatus.PHONE_PENDING));
        finder.answer("process-3", record("process-3", ReservationRecordStatus.PHONE_PENDING));
        finder.answer("process-4", record("process-4", ReservationRecordStatus.PHONE_PENDING));

        useCase.reconcilePendingProcesses();

        assertThat(output).contains("Reservation process process-1 reconciled with Catedra");
        assertThat(output).contains("Reservation process process-2 already matched Catedra");
        assertThat(output).contains("Reservation process process-3 stays PHONE_SUBMITTED");
        assertThat(output).contains("Reservation process process-4 is INVALID here, but Catedra has PHONE_PENDING");
        assertThat(output).contains("Reconciliation of " + BEFORE_EXPIRING + " finished: 1 corrected, 1 already matched, "
                + "1 still pending, 1 unresolved, 0 discarded, 0 failed, 0 left for the next cycle");
    }

    @Test
    void aCycleWithoutProcessesStillLeavesItsLine(CapturedOutput output) {
        useCase.reconcilePendingProcesses();

        assertThat(output).contains("Reconciliation of " + BEFORE_EXPIRING + " finished: 0 corrected");
    }

    // Lo que cambia mientras se consulta a la cátedra

    @Test
    void aProcessThatDisappearedWhileCatedraWasConsultedIsDiscarded(CapturedOutput output) {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED)));
        ReservationProcessRepository disappearing = new DisappearingRepository(repository);
        finder.answer(PROCESS, confirmed(PROCESS));

        useCaseWith(disappearing).reconcilePendingProcesses();

        assertThat(output).contains("Reconciliation of reservation process process-1 discarded: it changed while Catedra was consulted");
        assertThat(output).contains("1 discarded");
    }

    @Test
    void aResultThatArrivedWhileCatedraWasConsultedIsNotOverwritten(CapturedOutput output) {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED)));
        finder.answer(PROCESS, record(PROCESS, ReservationRecordStatus.FAILED));
        finder.whileConsulting(() -> {
            ReservationProcess confirmed = repository.stored(PROCESS);
            confirmed.confirmReservation(8251L, CONFIRMED_AT, null, null, null);
            repository.add(confirmed);
        });

        useCase.reconcilePendingProcesses();

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(repository.history(PROCESS)).isEmpty();
        assertThat(output).contains("Reconciliation of reservation process process-1 discarded: it changed while Catedra was consulted");
    }

    @Test
    void aMarkThatChangedWhileCatedraWasConsultedAlsoDiscardsTheAnswer() {
        repository.add(process(PROCESS, USER, ReservationStatus.WAITING_FOR_PHONE));
        finder.answer(PROCESS, record(PROCESS, ReservationRecordStatus.PHONE_PENDING));
        finder.whileConsulting(() -> repository.markForReconciliation(PROCESS));

        useCase.reconcilePendingProcesses();

        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isTrue();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aChangeOfAnotherProcessWhileCatedraWasConsultedDoesNotMatter() {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED)));
        repository.add(process("process-other", 7L, ReservationStatus.EXPIRED));
        finder.answer(PROCESS, confirmed(PROCESS));
        finder.whileConsulting(() -> repository.markForReconciliation("process-other"));

        useCase.reconcilePendingProcesses();

        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void theUserDoesNotWaitForCatedraWhileTheReconciliationConsultsIt() throws InterruptedException {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED)));
        finder.answer(PROCESS, confirmed(PROCESS));
        CountDownLatch consulting = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        finder.whileConsulting(() -> {
            consulting.countDown();
            await(answer);
        });
        CountDownLatch finished = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            useCase.reconcilePendingProcesses();
            finished.countDown();
        });
        assertThat(consulting.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch operated = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> userOperationLock.exclusivelyFor(USER, () -> {
            operated.countDown();
            return null;
        }));

        assertThat(operated.await(300, TimeUnit.MILLISECONDS)).isTrue();
        answer.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void theReconciliationWaitsForTheOperationThatHoldsTheLockAndRespectsWhatItLeft() throws InterruptedException {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED)));
        finder.answer(PROCESS, record(PROCESS, ReservationRecordStatus.FAILED));
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> userOperationLock.exclusivelyFor(USER, () -> {
            taken.countDown();
            await(release);
            ReservationProcess rejected = repository.stored(PROCESS);
            rejected.rejectPhone("INVALID_PHONE_NUMBER", EXPIRES_AT.plusSeconds(600));
            repository.add(rejected);
            return null;
        }));
        assertThat(taken.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch finished = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            useCase.reconcilePendingProcesses();
            finished.countDown();
        });

        assertThat(finished.await(300, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void anOperationThatLeftTheProcessAsItWasLetsTheReconciliationApplyItsAnswer() throws InterruptedException {
        repository.add(marked(process(PROCESS, USER, ReservationStatus.PHONE_SUBMITTED)));
        finder.answer(PROCESS, confirmed(PROCESS));
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
            useCase.reconcilePendingProcesses();
            finished.countDown();
        });

        assertThat(finished.await(300, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    private ReconcilePendingProcessesUseCaseImpl useCaseWith(ReservationProcessRepository processRepository) {
        return new ReconcilePendingProcessesUseCaseImpl(processRepository, finder,
                new ReservationPolicy(ZoneId.of("America/Argentina/Mendoza"), MARGIN), clock, userOperationLock);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ReservationRecord confirmed(String reservationProcessId) {
        return new ReservationRecord(reservationProcessId, ReservationRecordStatus.CONFIRMED, 8251L, CONFIRMED_AT, null,
                null, 15L, DAY, TIME);
    }

    private static ReservationRecord record(String reservationProcessId, ReservationRecordStatus status) {
        return new ReservationRecord(reservationProcessId, status, 8251L, null, null, null, null, null, null);
    }

    private static ReservationProcess marked(ReservationProcess process) {
        process.markForReconciliation();
        return process;
    }

    private static ReservationProcess invalid(ReservationProcess process) {
        process.invalidate();
        return process;
    }

    private static ReservationProcess withPhone(ReservationProcess process) {
        process.setPhoneNumber(PHONE);
        return process;
    }

    private static ReservationProcess withExpiration(ReservationProcess process, Instant expiresAt) {
        process.setExpiresAt(expiresAt);
        return process;
    }

    private static ReservationProcess process(String reservationProcessId, Long owner, ReservationStatus status) {
        return ReservationProcess.builder().reservationProcessId(reservationProcessId).holdId("hold-" + reservationProcessId)
                .userId(owner).status(status).expiresAt(EXPIRES_AT).professionalId(15L).date(DAY).startTime(TIME).build();
    }

    // Contesta lo indicado para cada proceso (sin indicación, no hay registro) y guarda qué se consultó. Lo que se le
    // pide hacer mientras consulta pasa en ese momento, antes de devolver la respuesta
    private static final class ScriptedAppointmentFinder implements AppointmentFinder {

        private final Map<String, Supplier<Optional<ReservationRecord>>> answers = new ConcurrentHashMap<>();
        private final List<String> consulted = new ArrayList<>();
        private final List<String> patients = new ArrayList<>();
        private volatile Runnable whileConsulting = () -> { };

        void answer(String reservationProcessId, ReservationRecord record) {
            answers.put(reservationProcessId, () -> Optional.of(record));
        }

        void fail(String reservationProcessId, Supplier<RuntimeException> error) {
            answers.put(reservationProcessId, () -> {
                throw error.get();
            });
        }

        void whileConsulting(Runnable action) {
            whileConsulting = action;
        }

        synchronized void clear() {
            consulted.clear();
            patients.clear();
            answers.clear();
        }

        synchronized List<String> consulted() {
            return new ArrayList<>(consulted);
        }

        synchronized List<String> patients() {
            return new ArrayList<>(patients);
        }

        @Override
        public Optional<ReservationRecord> find(String externalPatientId, String reservationProcessId) {
            synchronized (this) {
                consulted.add(reservationProcessId);
                patients.add(externalPatientId);
            }
            whileConsulting.run();
            return answers.getOrDefault(reservationProcessId, Optional::empty).get();
        }
    }

    // Encuentra el proceso al buscar, pero ya no está cuando se lo vuelve a leer
    private static final class DisappearingRepository implements ReservationProcessRepository {

        private final CopyingReservationProcessRepository delegate;

        DisappearingRepository(CopyingReservationProcessRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ReservationProcess> findToReconcile(boolean includeOpen) {
            return delegate.findToReconcile(includeOpen);
        }

        @Override
        public Optional<ReservationProcess> findById(String reservationProcessId) {
            return Optional.empty();
        }

        @Override
        public ReservationProcess create(ReservationProcess reservationProcess) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ReservationProcess> update(String reservationProcessId, ReservationProcess reservationProcess) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ReservationProcess> findOpenByUserId(Long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ReservationProcess> findByIdAndUserId(String reservationProcessId, Long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReservationProcessPage findListable(ReservationListCriteria criteria) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ReservationProcess> findOverdue(Instant expiredBefore) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markForReconciliation(String reservationProcessId) {
            throw new UnsupportedOperationException();
        }
    }
}
