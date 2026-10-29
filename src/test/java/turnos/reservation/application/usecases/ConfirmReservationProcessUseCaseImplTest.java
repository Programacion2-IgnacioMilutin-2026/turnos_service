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
import turnos.reservation.application.exception.ReservationHoldNotFoundException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationInvalidProcessStateException;
import turnos.reservation.application.exception.ReservationInvalidSlotException;
import turnos.reservation.application.exception.ReservationLocalStateOutOfSyncException;
import turnos.reservation.application.exception.ReservationPatientMismatchException;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.application.exception.ReservationProfessionalNotFoundException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.application.exception.ReservationSlotNotAvailableException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.domain.model.ReservationHold;
import turnos.reservation.domain.model.ReservationHoldConfirmation;
import turnos.reservation.domain.model.ReservationPatient;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProfessional;
import turnos.reservation.domain.model.ReservationSchedule;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.HoldProvider;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.domain.ports.out.UserDirectory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ConfirmReservationProcessUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-11-02T13:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(600);
    private static final Long USER = 42L;
    private static final String PROCESS = "process-1";
    private static final ReservationPatient PATIENT = new ReservationPatient("María", "Gómez");
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");

    private final MutableClock clock = new MutableClock(NOW);
    private final CopyingRepository repository = new CopyingRepository();
    private final Directory users = new Directory(Map.of(USER, PATIENT, 7L, new ReservationPatient("Juan", "Pérez")));
    private final ScriptedHoldProvider holds = new ScriptedHoldProvider();
    private final UserOperationLock userOperationLock = new UserOperationLock();
    private final ConfirmReservationProcessUseCaseImpl useCase = useCase(Duration.ofSeconds(30));

    @Test
    void aProcessThatDoesNotExistIsNotFoundWithoutCallingTheCatedra() {
        assertThatThrownBy(() -> confirm(USER, PROCESS)).isInstanceOf(ReservationProcessNotFoundException.class);
        assertThat(holds.confirmations()).isEmpty();
    }

    @Test
    void aProcessOfAnotherUserIsNotFoundEither() {
        repository.add(held(PROCESS, 7L));

        assertThatThrownBy(() -> confirm(USER, PROCESS)).isInstanceOf(ReservationProcessNotFoundException.class);
        assertThat(holds.confirmations()).isEmpty();
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void theTokenOfAUserThatNoLongerExistsFindsNoProcess() {
        assertThatThrownBy(() -> confirm(999L, PROCESS)).isInstanceOf(ReservationProcessNotFoundException.class);
        assertThat(holds.confirmations()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"WAITING_FOR_PHONE", "PHONE_SUBMITTED", "EXPIRED"})
    void onlyAHeldProcessIsConfirmedAndTheCurrentStatusIsReported(ReservationStatus status) {
        ReservationProcess process = held(PROCESS, USER);
        process.setStatus(status);
        repository.add(process);

        assertThatThrownBy(() -> confirm(USER, PROCESS))
                .isInstanceOfSatisfying(ReservationInvalidProcessStateException.class,
                        e -> assertThat(e.getCurrentStatus()).isEqualTo(status));
        assertThat(holds.confirmations()).isEmpty();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void withinTheMarginTheCatedraDecides() {
        repository.add(held(PROCESS, USER));
        clock.advance(Duration.between(NOW, EXPIRES_AT).plusSeconds(29));

        assertThat(confirm(USER, PROCESS).getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(holds.confirmations()).hasSize(1);
    }

    @Test
    void pastTheMarginTheProcessExpiresWithoutCallingTheCatedra() {
        repository.add(held(PROCESS, USER));
        clock.advance(Duration.between(NOW, EXPIRES_AT).plusSeconds(31));

        assertThatThrownBy(() -> confirm(USER, PROCESS)).isInstanceOf(ReservationHoldExpiredException.class);
        assertThat(holds.confirmations()).isEmpty();
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void anotherConfiguredMarginIsUsed() {
        repository.add(held(PROCESS, USER));
        clock.advance(Duration.between(NOW, EXPIRES_AT).plusSeconds(11));

        assertThatThrownBy(() -> useCase(Duration.ofSeconds(10)).confirmReservationProcess(USER, PROCESS))
                .isInstanceOf(ReservationHoldExpiredException.class);
        assertThat(holds.confirmations()).isEmpty();
    }

    @Test
    void aConfirmedHoldWaitsForThePhoneUntilTheExpirationOfTheCatedra() {
        repository.add(held(PROCESS, USER));
        Instant confirmedExpiresAt = EXPIRES_AT.plusSeconds(1);
        holds.answer(() -> ReservationHoldConfirmation.builder().expiresAt(confirmedExpiresAt).build());

        ReservationProcess confirmed = confirm(USER, PROCESS);

        assertThat(holds.confirmations()).containsExactly("hold-1 201 42 María Gómez");
        assertThat(confirmed.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(confirmed.getExpiresAt()).isEqualTo(confirmedExpiresAt);
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(stored.getExpiresAt()).isEqualTo(confirmedExpiresAt);
        assertThat(stored.isNeedsReconciliation()).isFalse();
    }

    @Test
    void anAlreadyConfirmedHoldWaitsForThePhoneWithTheSameExpiration() {
        repository.add(held(PROCESS, USER));
        holds.answer(() -> ReservationHoldConfirmation.builder().alreadyConfirmed(true).build());

        ReservationProcess confirmed = confirm(USER, PROCESS);

        assertThat(confirmed.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(confirmed.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
    }

    @Test
    void aHoldExpiredForTheCatedraClosesTheProcess() {
        repository.add(held(PROCESS, USER));
        holds.answer(() -> {
            throw new ReservationHoldExpiredException("hold-1");
        });

        assertThatThrownBy(() -> confirm(USER, PROCESS)).isInstanceOf(ReservationHoldExpiredException.class);
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void aHoldNotFoundAfterItsExpirationIsAnExpiredHold() {
        repository.add(held(PROCESS, USER));
        clock.advance(Duration.between(NOW, EXPIRES_AT).plusSeconds(10));
        holds.answer(() -> {
            throw new ReservationHoldNotFoundException("hold-1");
        });

        assertThatThrownBy(() -> confirm(USER, PROCESS)).isInstanceOf(ReservationHoldExpiredException.class);
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void aHoldNotFoundBeforeItsExpirationIsReportedAsItIs() {
        repository.add(held(PROCESS, USER));
        holds.answer(() -> {
            throw new ReservationHoldNotFoundException("hold-1");
        });

        assertThatThrownBy(() -> confirm(USER, PROCESS)).isInstanceOf(ReservationHoldNotFoundException.class);
        assertThat(repository.stored(PROCESS).getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void otherRejectionsLeaveTheProcessAsItWas() {
        List<RuntimeException> rejections = List.of(new ReservationPatientMismatchException("hold-1"),
                new ReservationIntegrationException("Hold owner mismatch"), new ReservationProfessionalNotFoundException(201L),
                new ReservationInvalidSlotException(201L), new ReservationSlotNotAvailableException(201L),
                new ReservationServiceDegradedException("config"));
        repository.add(held(PROCESS, USER));

        for (RuntimeException rejection : rejections) {
            holds.answer(() -> {
                throw rejection;
            });

            assertThatThrownBy(() -> confirm(USER, PROCESS)).isSameAs(rejection);
        }
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(stored.isNeedsReconciliation()).isFalse();
        assertThat(repository.history(PROCESS)).isEmpty();
    }

    @Test
    void aCatedraThatDoesNotAnswerLeavesTheProcessHeldAndMarked() {
        repository.add(held(PROCESS, USER));
        holds.answer(() -> {
            throw new ReservationCentralUnavailableException("down");
        });

        assertThatThrownBy(() -> confirm(USER, PROCESS)).isInstanceOf(ReservationCentralUnavailableException.class);
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(stored.isNeedsReconciliation()).isTrue();
    }

    @Test
    void ifTheMarkCannotBeSavedTheCatedraIsStillTheProblem(CapturedOutput output) {
        repository.add(held(PROCESS, USER));
        repository.failUpdatesWhen(process -> true);
        holds.answer(() -> {
            throw new ReservationCentralUnavailableException("down");
        });

        assertThatThrownBy(() -> confirm(USER, PROCESS)).isInstanceOf(ReservationCentralUnavailableException.class);
        assertThat(output.getAll()).contains("Reservation process process-1 could not be marked for reconciliation "
                + "after Catedra did not answer");
    }

    @Test
    void aConfirmationThatCannotBeSavedIsReportedAndMarked(CapturedOutput output) {
        repository.add(held(PROCESS, USER));
        repository.failUpdatesWhen(process -> process.getStatus() == ReservationStatus.WAITING_FOR_PHONE);

        assertThatThrownBy(() -> confirm(USER, PROCESS)).isInstanceOf(ReservationLocalStateOutOfSyncException.class);
        ReservationProcess stored = repository.stored(PROCESS);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(stored.isNeedsReconciliation()).isTrue();
        assertThat(output.getAll()).contains("ERROR")
                .contains("Catedra confirmed reservation process process-1 (hold hold-1) but it could not be saved");
    }

    @Test
    void ifNeitherTheConfirmationNorTheMarkCanBeSavedOnlyTheLogRemains(CapturedOutput output) {
        repository.add(held(PROCESS, USER));
        repository.failUpdatesWhen(process -> process.getStatus() == ReservationStatus.WAITING_FOR_PHONE);
        repository.failMarks();

        assertThatThrownBy(() -> confirm(USER, PROCESS)).isInstanceOf(ReservationLocalStateOutOfSyncException.class);
        assertThat(repository.stored(PROCESS).isNeedsReconciliation()).isFalse();
        assertThat(output.getAll()).contains("Catedra confirmed reservation process process-1")
                .contains("Reservation process process-1 could not be marked for reconciliation either");
    }

    @Test
    void aClosingThatCannotBeSavedIsNotReportedAsAnExpiredHold() {
        repository.add(held(PROCESS, USER));
        repository.failUpdatesWhen(process -> true);
        clock.advance(Duration.between(NOW, EXPIRES_AT).plusSeconds(31));

        assertThatThrownBy(() -> confirm(USER, PROCESS)).isInstanceOf(IllegalStateException.class)
                .hasMessage("database down");
    }

    @Test
    void twoSimultaneousConfirmationsConfirmTheHoldOnlyOnce() throws InterruptedException {
        repository.add(held(PROCESS, USER));
        holds.beforeAnswering(() -> sleep(200));
        List<Object> results = new CopyOnWriteArrayList<>();

        Thread first = Thread.ofVirtual().start(() -> results.add(attempt(() -> confirm(USER, PROCESS))));
        Thread second = Thread.ofVirtual().start(() -> results.add(attempt(() -> confirm(USER, PROCESS))));
        first.join(10000);
        second.join(10000);

        assertThat(holds.confirmations()).hasSize(1);
        assertThat(results).hasSize(2).anyMatch(ReservationProcess.class::isInstance)
                .anySatisfy(result -> assertThat(result).isInstanceOfSatisfying(ReservationInvalidProcessStateException.class,
                        e -> assertThat(e.getCurrentStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE)));
    }

    // Mientras la cátedra tarda en confirmar, el proceso vence según el margen y el mismo usuario pide otro bloqueo:
    // el bloqueo espera, así que el proceso nunca vuelve de EXPIRED a otro estado
    @Test
    void aBlockOfTheSameUserNeverReopensAClosedProcess() throws InterruptedException {
        repository.add(held(PROCESS, USER));
        clock.advance(Duration.between(NOW, EXPIRES_AT).plusSeconds(29));
        CountDownLatch confirmationRequested = new CountDownLatch(1);
        holds.beforeAnswering(() -> {
            confirmationRequested.countDown();
            sleep(300);
        });
        CreateReservationProcessUseCaseImpl block = new CreateReservationProcessUseCaseImpl(repository, users,
                (professionalId, userToken) -> professional(), holds, new ReservationPolicy(ZONE, Duration.ofSeconds(30)),
                clock, userOperationLock);

        Thread confirmation = Thread.ofVirtual().start(() -> attempt(() -> confirm(USER, PROCESS)));
        assertThat(confirmationRequested.await(5, TimeUnit.SECONDS)).isTrue();
        clock.advance(Duration.ofSeconds(2));
        Thread blocking = Thread.ofVirtual().start(() -> attempt(() -> block.createReservationProcess(USER, 201L,
                LocalDate.of(2026, 11, 9), LocalTime.of(10, 30), "user-jwt")));
        confirmation.join(10000);
        blocking.join(10000);

        List<ReservationStatus> history = repository.history(PROCESS);
        assertThat(history).isNotEmpty();
        int closedAt = history.indexOf(ReservationStatus.EXPIRED);
        if (closedAt >= 0) {
            assertThat(history.subList(closedAt, history.size())).containsOnly(ReservationStatus.EXPIRED);
        }
    }

    private ReservationProcess confirm(Long userId, String reservationProcessId) {
        return useCase.confirmReservationProcess(userId, reservationProcessId);
    }

    private ConfirmReservationProcessUseCaseImpl useCase(Duration margin) {
        return new ConfirmReservationProcessUseCaseImpl(repository, users, holds, new ReservationPolicy(ZONE, margin),
                clock, userOperationLock);
    }

    private static Object attempt(Supplier<Object> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static ReservationProcess held(String reservationProcessId, Long userId) {
        return ReservationProcess.builder()
                .reservationProcessId(reservationProcessId)
                .holdId("hold-1")
                .userId(userId)
                .professionalId(201L)
                .professionalFirstName("Ana")
                .professionalLastName("Gomez")
                .professionalCategoryName("Clinica medica")
                .date(LocalDate.of(2026, 11, 9))
                .startTime(LocalTime.of(10, 30))
                .status(ReservationStatus.HELD)
                .expiresAt(EXPIRES_AT)
                .build();
    }

    private static ReservationProfessional professional() {
        return ReservationProfessional.builder().enabled(true).firstName("Ana").lastName("Gomez")
                .categoryName("Clinica medica")
                .schedules(List.of(ReservationSchedule.builder().dayOfWeek(DayOfWeek.MONDAY).startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(12, 0)).slotDurationMinutes(30).enabled(true).build()))
                .build();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Como la base: guarda y devuelve copias, así cada operación trabaja sobre su propia lectura. Registra cada estado
    // escrito por proceso, y puede fallar al actualizar o al marcar
    private static final class CopyingRepository implements ReservationProcessRepository {

        private final Map<String, ReservationProcess> processes = new LinkedHashMap<>();
        private final Map<String, List<ReservationStatus>> histories = new LinkedHashMap<>();
        private volatile Predicate<ReservationProcess> failUpdate = process -> false;
        private volatile boolean failMarks;

        synchronized void add(ReservationProcess process) {
            processes.put(process.getReservationProcessId(), copy(process));
        }

        synchronized ReservationProcess stored(String reservationProcessId) {
            return copy(processes.get(reservationProcessId));
        }

        synchronized List<ReservationStatus> history(String reservationProcessId) {
            return new ArrayList<>(histories.getOrDefault(reservationProcessId, List.of()));
        }

        void failUpdatesWhen(Predicate<ReservationProcess> condition) {
            failUpdate = condition;
        }

        void failMarks() {
            failMarks = true;
        }

        @Override
        public synchronized ReservationProcess create(ReservationProcess process) {
            add(process);
            histories.computeIfAbsent(process.getReservationProcessId(), id -> new ArrayList<>()).add(process.getStatus());
            return copy(process);
        }

        @Override
        public synchronized Optional<ReservationProcess> update(String reservationProcessId, ReservationProcess process) {
            if (!processes.containsKey(reservationProcessId)) {
                return Optional.empty();
            }
            if (failUpdate.test(process)) {
                throw new IllegalStateException("database down");
            }
            processes.put(reservationProcessId, copy(process));
            histories.computeIfAbsent(reservationProcessId, id -> new ArrayList<>()).add(process.getStatus());
            return Optional.of(copy(process));
        }

        @Override
        public synchronized List<ReservationProcess> findOpenByUserId(Long userId) {
            return processes.values().stream().filter(p -> p.getUserId().equals(userId) && p.isOpen())
                    .map(CopyingRepository::copy).toList();
        }

        @Override
        public synchronized Optional<ReservationProcess> findByIdAndUserId(String reservationProcessId, Long userId) {
            return Optional.ofNullable(processes.get(reservationProcessId))
                    .filter(p -> p.getUserId().equals(userId)).map(CopyingRepository::copy);
        }

        @Override
        public synchronized void markForReconciliation(String reservationProcessId) {
            if (failMarks) {
                throw new IllegalStateException("database down");
            }
            processes.get(reservationProcessId).markForReconciliation();
        }

        private static ReservationProcess copy(ReservationProcess p) {
            return ReservationProcess.builder().reservationProcessId(p.getReservationProcessId()).holdId(p.getHoldId())
                    .userId(p.getUserId()).professionalId(p.getProfessionalId())
                    .professionalFirstName(p.getProfessionalFirstName()).professionalLastName(p.getProfessionalLastName())
                    .professionalCategoryName(p.getProfessionalCategoryName()).date(p.getDate())
                    .startTime(p.getStartTime()).status(p.getStatus()).expiresAt(p.getExpiresAt())
                    .needsReconciliation(p.isNeedsReconciliation()).build();
        }
    }

    private record Directory(Map<Long, ReservationPatient> patients) implements UserDirectory {

        @Override
        public boolean exists(Long userId) {
            return patients.containsKey(userId);
        }

        @Override
        public Optional<ReservationPatient> findPatient(Long userId) {
            return Optional.ofNullable(patients.get(userId));
        }
    }

    // Por defecto confirma con el vencimiento del hold; guarda cada confirmación pedida. Un bloqueo recibe otro hold
    private static final class ScriptedHoldProvider implements HoldProvider {

        private final List<String> confirmations = new CopyOnWriteArrayList<>();
        private volatile Supplier<ReservationHoldConfirmation> answer =
                () -> ReservationHoldConfirmation.builder().expiresAt(EXPIRES_AT).build();
        private volatile Runnable beforeAnswering = () -> { };

        void answer(Supplier<ReservationHoldConfirmation> next) {
            answer = next;
        }

        void beforeAnswering(Runnable action) {
            beforeAnswering = action;
        }

        List<String> confirmations() {
            return confirmations;
        }

        @Override
        public ReservationHoldConfirmation confirmHold(String holdId, Long professionalId, String externalPatientId,
                                                       ReservationPatient patient) {
            confirmations.add(holdId + " " + professionalId + " " + externalPatientId + " " + patient.getFirstName()
                    + " " + patient.getLastName());
            beforeAnswering.run();
            return answer.get();
        }

        @Override
        public ReservationHold requestHold(Long professionalId, LocalDate date, LocalTime startTime, String externalPatientId) {
            return ReservationHold.builder().holdId("hold-2").reservationProcessId("process-2")
                    .expiresAt(EXPIRES_AT.plusSeconds(600)).build();
        }
    }
}
