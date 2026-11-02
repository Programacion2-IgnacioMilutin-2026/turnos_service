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
import turnos.reservation.domain.model.PhoneRequestOutcome;
import turnos.reservation.domain.model.ReservationPhoneRequest;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.TransactionRunner;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ProcessPhoneRequestUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-11-02T13:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(600);
    private static final Instant REQUEST_EXPIRES_AT = EXPIRES_AT.plusSeconds(1);
    private static final Long USER = 42L;
    private static final String PROCESS = "process-1";

    private final CopyingReservationProcessRepository processes = new CopyingReservationProcessRepository();
    private final InMemoryProcessedEventRepository events = new InMemoryProcessedEventRepository();
    private final UserOperationLock userOperationLock = new UserOperationLock();
    private final ProcessPhoneRequestUseCaseImpl useCase = useCase(Runnable::run);

    @Test
    void aProcessWaitingForThePhoneKeepsTheRequestAndItsExpirationAndTheRequestIsRegistered() {
        processes.add(process(PROCESS, USER, ReservationStatus.WAITING_FOR_PHONE));

        assertThat(useCase.processPhoneRequest(request("request-event-1"))).isEqualTo(PhoneRequestOutcome.APPLIED);

        ReservationProcess stored = processes.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(stored.getRequestEventId()).isEqualTo("request-event-1");
        assertThat(stored.getExpiresAt()).isEqualTo(REQUEST_EXPIRES_AT);
        assertThat(events.registered()).containsExactly(Map.entry("request-event-1", NOW));
    }

    @Test
    void aHeldProcessStartsWaitingForThePhoneAndKeepsItsReconciliationMark() {
        ReservationProcess held = process(PROCESS, USER, ReservationStatus.HELD);
        held.markForReconciliation();
        processes.add(held);

        assertThat(useCase.processPhoneRequest(request("request-event-1"))).isEqualTo(PhoneRequestOutcome.APPLIED);

        ReservationProcess stored = processes.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(stored.getRequestEventId()).isEqualTo("request-event-1");
        assertThat(stored.isNeedsReconciliation()).isTrue();
    }

    @Test
    void aRepeatedRequestHasNoEffectEvenIfTheProcessChangedInBetween(CapturedOutput output) {
        processes.add(process(PROCESS, USER, ReservationStatus.WAITING_FOR_PHONE));
        useCase.processPhoneRequest(request("request-event-1"));
        ReservationProcess submitted = processes.stored(PROCESS);
        submitted.setStatus(ReservationStatus.PHONE_SUBMITTED);
        processes.update(PROCESS, submitted);

        assertThat(useCase.processPhoneRequest(request("request-event-1"))).isEqualTo(PhoneRequestOutcome.ALREADY_PROCESSED);

        assertThat(processes.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
        assertThat(events.registered()).hasSize(1);
        assertThat(output.getAll()).contains("Phone request request-event-1 for reservation process process-1 was already processed");
    }

    @Test
    void aRequestForAProcessThatDoesNotExistIsAnOrphanAndIsNotRegistered(CapturedOutput output) {
        assertThat(useCase.processPhoneRequest(request("request-event-1"))).isEqualTo(PhoneRequestOutcome.ORPHAN);

        assertThat(events.registered()).isEmpty();
        assertThat(output.getAll()).contains("WARN")
                .contains("Phone request request-event-1 refers to reservation process process-1, which does not exist here");
    }

    @Test
    void aRequestWithAnotherHoldChangesNothing(CapturedOutput output) {
        processes.add(process(PROCESS, USER, ReservationStatus.WAITING_FOR_PHONE));
        ReservationPhoneRequest otherHold = request("request-event-1");
        otherHold.setHoldId("hold-other");

        assertThat(useCase.processPhoneRequest(otherHold)).isEqualTo(PhoneRequestOutcome.HOLD_MISMATCH);

        assertThat(processes.stored(PROCESS).getRequestEventId()).isNull();
        assertThat(processes.history(PROCESS)).isEmpty();
        assertThat(events.registered()).isEmpty();
        assertThat(output.getAll()).contains("WARN")
                .contains("Phone request request-event-1 has hold hold-other, but reservation process process-1 has hold hold-1");
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"PHONE_SUBMITTED", "EXPIRED"})
    void aProcessThatAlreadyAdvancedIsNotChanged(ReservationStatus status, CapturedOutput output) {
        processes.add(process(PROCESS, USER, status));

        assertThat(useCase.processPhoneRequest(request("request-event-1"))).isEqualTo(PhoneRequestOutcome.STATE_ADVANCED);

        assertThat(processes.stored(PROCESS).getStatus()).isEqualTo(status);
        assertThat(processes.stored(PROCESS).getRequestEventId()).isNull();
        assertThat(processes.history(PROCESS)).isEmpty();
        assertThat(events.registered()).isEmpty();
        assertThat(output.getAll())
                .contains("Phone request request-event-1 ignored: reservation process process-1 is already " + status);
    }

    @Test
    void ifTheDatabaseFailsNothingChangesAndTheFailureArrives() {
        processes.add(process(PROCESS, USER, ReservationStatus.HELD));
        ProcessPhoneRequestUseCaseImpl failing = useCase(work -> {
            throw new ReservationNotSavedException(new IllegalStateException("database down"));
        });

        assertThatThrownBy(() -> failing.processPhoneRequest(request("request-event-1")))
                .isInstanceOf(ReservationNotSavedException.class);

        assertThat(processes.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(events.registered()).isEmpty();
    }

    @Test
    void theRequestWaitsForAnotherOperationOfTheSameUserButNotOfAnotherOne() throws Exception {
        processes.add(process(PROCESS, USER, ReservationStatus.HELD));
        processes.add(process("process-2", 7L, ReservationStatus.HELD));
        CountDownLatch lockTaken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread operationOfTheUser = Thread.ofVirtual().start(() -> userOperationLock.exclusivelyFor(USER, () -> {
            lockTaken.countDown();
            await(release);
            return null;
        }));
        assertThat(lockTaken.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<PhoneRequestOutcome> sameUser = CompletableFuture.supplyAsync(
                () -> useCase.processPhoneRequest(request("request-event-1")));
        ReservationPhoneRequest otherUserRequest = request("request-event-2");
        otherUserRequest.setReservationProcessId("process-2");
        PhoneRequestOutcome otherUser = CompletableFuture.supplyAsync(() -> useCase.processPhoneRequest(otherUserRequest))
                .get(5, TimeUnit.SECONDS);

        assertThat(otherUser).isEqualTo(PhoneRequestOutcome.APPLIED);
        Thread.sleep(200);
        assertThat(sameUser).isNotDone();
        assertThat(processes.stored(PROCESS).getRequestEventId()).isNull();

        release.countDown();
        assertThat(sameUser.get(5, TimeUnit.SECONDS)).isEqualTo(PhoneRequestOutcome.APPLIED);
        operationOfTheUser.join(5000);
    }

    @Test
    void aProcessClosedWhileTheRequestWaitedIsNotReopened() throws Exception {
        processes.add(process(PROCESS, USER, ReservationStatus.HELD));
        CountDownLatch lockTaken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread operationOfTheUser = Thread.ofVirtual().start(() -> userOperationLock.exclusivelyFor(USER, () -> {
            lockTaken.countDown();
            await(release);
            ReservationProcess expired = processes.stored(PROCESS);
            expired.setStatus(ReservationStatus.EXPIRED);
            return processes.update(PROCESS, expired);
        }));
        assertThat(lockTaken.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<PhoneRequestOutcome> request = CompletableFuture.supplyAsync(
                () -> useCase.processPhoneRequest(request("request-event-1")));
        Thread.sleep(200);
        release.countDown();

        assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo(PhoneRequestOutcome.STATE_ADVANCED);
        operationOfTheUser.join(5000);
        assertThat(processes.history(PROCESS)).containsExactly(ReservationStatus.EXPIRED);
        assertThat(events.registered()).isEmpty();
    }

    private ProcessPhoneRequestUseCaseImpl useCase(TransactionRunner transactionRunner) {
        return new ProcessPhoneRequestUseCaseImpl(processes, events, transactionRunner, userOperationLock,
                new MutableClock(NOW));
    }

    private static ReservationPhoneRequest request(String eventId) {
        return ReservationPhoneRequest.builder().eventId(eventId).reservationProcessId(PROCESS).holdId("hold-1")
                .expiresAt(REQUEST_EXPIRES_AT).build();
    }

    private static ReservationProcess process(String reservationProcessId, Long userId, ReservationStatus status) {
        return ReservationProcess.builder().reservationProcessId(reservationProcessId).holdId("hold-1").userId(userId)
                .professionalId(201L).date(LocalDate.of(2026, 11, 9)).startTime(LocalTime.of(10, 30)).status(status)
                .expiresAt(EXPIRES_AT).build();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
