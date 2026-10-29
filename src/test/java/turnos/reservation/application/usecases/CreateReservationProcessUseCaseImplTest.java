package turnos.reservation.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import turnos.MutableClock;
import turnos.reservation.application.exception.ReservationCatalogUnavailableException;
import turnos.reservation.application.exception.ReservationHoldNotSavedException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationInvalidSlotException;
import turnos.reservation.application.exception.ReservationOpenProcessConflictException;
import turnos.reservation.application.exception.ReservationOpenProcessExistsException;
import turnos.reservation.application.exception.ReservationProfessionalDisabledException;
import turnos.reservation.application.exception.ReservationProfessionalNotFoundException;
import turnos.reservation.application.exception.ReservationSlotInPastException;
import turnos.reservation.application.exception.ReservationSlotNotAvailableException;
import turnos.reservation.application.exception.ReservationUserNotFoundException;
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
import turnos.reservation.domain.ports.out.ProfessionalProvider;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.domain.ports.out.UserDirectory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CreateReservationProcessUseCaseImplTest {

    // Lunes 2 de noviembre a las 10:00 en Mendoza (13:00 UTC)
    private static final Instant NOW = Instant.parse("2026-11-02T13:00:00Z");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
    private static final LocalTime TEN_THIRTY = LocalTime.of(10, 30);
    private static final Long USER = 42L;
    private static final String TOKEN = "user-jwt";
    private static final ReservationProfessional PROFESSIONAL = ReservationProfessional.builder().enabled(true)
            .firstName("Ana").lastName("Gomez").categoryName("Clinica medica")
            .schedules(List.of(ReservationSchedule.builder().dayOfWeek(DayOfWeek.MONDAY).startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(12, 0)).slotDurationMinutes(30).enabled(true).build()))
            .build();
    private static final Instant HOLD_EXPIRES_AT = NOW.plusSeconds(600);

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryReservationProcessRepository repository = new InMemoryReservationProcessRepository();
    private final Set<Long> users = Collections.synchronizedSet(new java.util.HashSet<>(Set.of(USER, 7L)));
    private final ScriptedProfessionalProvider professionals = new ScriptedProfessionalProvider();
    private final ScriptedHoldProvider holds = new ScriptedHoldProvider();
    private final UserOperationLock userOperationLock = new UserOperationLock();
    private final CreateReservationProcessUseCaseImpl useCase = new CreateReservationProcessUseCaseImpl(repository,
            new KnownUsers(users), professionals, holds,
            new ReservationPolicy(ZoneId.of("America/Argentina/Mendoza"), Duration.ofSeconds(30)), clock,
            userOperationLock);

    @Test
    void aSlotOfAnEarlierDayIsRejectedWithoutCallingAnyone() {
        assertThatThrownBy(() -> create(MONDAY.minusDays(1), TEN_THIRTY))
                .isInstanceOfSatisfying(ReservationSlotInPastException.class, e -> assertThat(e.getField()).isEqualTo("date"));
        assertNothingWasCalled();
    }

    @Test
    void aSlotOfTodayThatAlreadyStartedOrStartsRightNowIsRejected() {
        for (LocalTime started : List.of(LocalTime.of(9, 30), LocalTime.of(10, 0))) {
            assertThatThrownBy(() -> create(MONDAY, started)).isInstanceOfSatisfying(ReservationSlotInPastException.class,
                    e -> assertThat(e.getField()).isEqualTo("startTime"));
        }
        assertNothingWasCalled();
    }

    @Test
    void aTokenOfAUserThatDoesNotExistIsRejectedBeforeAnything() {
        assertThatThrownBy(() -> useCase.createReservationProcess(999L, 201L, MONDAY, TEN_THIRTY, TOKEN))
                .isInstanceOf(ReservationUserNotFoundException.class);
        assertNothingWasCalled();
    }

    @Test
    void aSuccessfulHoldIsSavedAsAHeldProcessWithTheProfessionalData() {
        ReservationProcess created = create(MONDAY, TEN_THIRTY);

        assertThat(professionals.calls()).containsExactly("201 " + TOKEN);
        assertThat(holds.calls()).containsExactly("201 2026-11-02 10:30 42");
        assertThat(created).usingRecursiveComparison().isEqualTo(ReservationProcess.builder()
                .reservationProcessId("process-1").holdId("hold-1").userId(USER).professionalId(201L)
                .professionalFirstName("Ana").professionalLastName("Gomez").professionalCategoryName("Clinica medica")
                .date(MONDAY).startTime(TEN_THIRTY).status(ReservationStatus.HELD).expiresAt(HOLD_EXPIRES_AT).build());
        assertThat(repository.processes()).containsExactly(created);
    }

    @Test
    void anExpiredProcessIsClosedFirstAndTheNewHoldGoesOn() {
        ReservationProcess old = repository.add(process("process-old", USER, ReservationStatus.HELD, NOW.minusSeconds(31)));

        create(MONDAY, TEN_THIRTY);

        assertThat(old.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(repository.updates()).containsExactly("process-old");
        assertThat(holds.calls()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED"})
    void anOpenProcessOfTheUserPreventsANewOneWithoutCallingOutside(ReservationStatus status) {
        ReservationProcess open = repository.add(process("process-open", USER, status, NOW.minusSeconds(29)));

        assertThatThrownBy(() -> create(MONDAY, TEN_THIRTY)).isInstanceOfSatisfying(
                ReservationOpenProcessExistsException.class, e -> assertThat(e.getOpenProcess()).isSameAs(open));
        assertThat(professionals.calls()).isEmpty();
        assertThat(holds.calls()).isEmpty();
    }

    @Test
    void aProcessWithThePhoneSubmittedStaysOpenEvenAfterItsExpiration() {
        repository.add(process("process-phone", USER, ReservationStatus.PHONE_SUBMITTED, NOW.minusSeconds(3600)));

        assertThatThrownBy(() -> create(MONDAY, TEN_THIRTY)).isInstanceOf(ReservationOpenProcessExistsException.class);
        assertThat(repository.updates()).isEmpty();
    }

    @Test
    void anOpenProcessOfAnotherUserDoesNotMatter() {
        repository.add(process("process-other", 7L, ReservationStatus.HELD, NOW.plusSeconds(300)));

        assertThat(create(MONDAY, TEN_THIRTY).getReservationProcessId()).isEqualTo("process-1");
    }

    @Test
    void eachCatalogProblemStopsBeforeTheCatedra() {
        for (RuntimeException problem : List.of(new ReservationProfessionalNotFoundException(201L),
                new ReservationCatalogUnavailableException("down"), new ReservationIntegrationException("rejected"))) {
            professionals.then(() -> {
                throw problem;
            });

            assertThatThrownBy(() -> create(MONDAY, TEN_THIRTY)).isSameAs(problem);
        }
        assertThat(holds.calls()).isEmpty();
        assertThat(repository.processes()).isEmpty();
    }

    @Test
    void aDisabledProfessionalStopsBeforeTheCatedraEvenIfTheSlotIsValid() {
        professionals.then(() -> ReservationProfessional.builder().enabled(false).schedules(PROFESSIONAL.getSchedules()).build());

        assertThatThrownBy(() -> create(MONDAY, TEN_THIRTY)).isInstanceOf(ReservationProfessionalDisabledException.class);
        assertThat(holds.calls()).isEmpty();
    }

    @Test
    void aSlotThatNoScheduleGeneratesStopsBeforeTheCatedra() {
        assertThatThrownBy(() -> create(MONDAY, LocalTime.of(10, 15))).isInstanceOf(ReservationInvalidSlotException.class);
        assertThat(holds.calls()).isEmpty();
    }

    @Test
    void whenTheCatedraDoesNotGrantTheHoldNothingIsSaved() {
        holds.then(() -> {
            throw new ReservationSlotNotAvailableException(201L);
        });

        assertThatThrownBy(() -> create(MONDAY, TEN_THIRTY)).isInstanceOf(ReservationSlotNotAvailableException.class);
        assertThat(repository.processes()).isEmpty();
    }

    @Test
    void aHoldThatCannotBeSavedIsReportedWithItsExpirationAndLogged(CapturedOutput output) {
        repository.failOnCreate(new IllegalStateException("database down"));

        assertThatThrownBy(() -> create(MONDAY, TEN_THIRTY)).isInstanceOfSatisfying(ReservationHoldNotSavedException.class,
                e -> {
                    assertThat(e.getExpiresAt()).isEqualTo(HOLD_EXPIRES_AT);
                    assertThat(e.getSecondsUntilExpiration()).isEqualTo(600);
                });
        assertThat(output.getAll()).contains("ERROR").contains("Hold hold-1 of reservation process process-1")
                .contains("could not be saved");
    }

    @Test
    void anOpenProcessSavedByAnotherInstanceInTheMeantimeIsReportedAndTheHoldLogged(CapturedOutput output) {
        ReservationProcess other = process("process-other-instance", USER, ReservationStatus.HELD, NOW.plusSeconds(300));
        holds.beforeAnswering(() -> repository.add(other));

        assertThatThrownBy(() -> create(MONDAY, TEN_THIRTY)).isInstanceOfSatisfying(
                ReservationOpenProcessExistsException.class, e -> assertThat(e.getOpenProcess()).isSameAs(other));
        assertThat(repository.processes()).containsExactly(other);
        assertThat(output.getAll()).contains("Hold hold-1 of reservation process process-1 was left without a local process");
    }

    @Test
    void twoSimultaneousRequestsOfTheSameUserRequestASingleHold() throws Exception {
        CountDownLatch firstHoldRequested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holds.beforeAnswering(() -> {
            firstHoldRequested.countDown();
            await(release);
        });
        List<Object> results = new CopyOnWriteArrayList<>();

        Thread first = Thread.ofVirtual().start(() -> results.add(attempt(MONDAY, TEN_THIRTY)));
        assertThat(firstHoldRequested.await(5, TimeUnit.SECONDS)).isTrue();
        Thread second = Thread.ofVirtual().start(() -> results.add(attempt(MONDAY, LocalTime.of(11, 0))));
        Thread.sleep(200);
        assertThat(second.isAlive()).as("the second request waits for the first one").isTrue();
        release.countDown();
        first.join(5000);
        second.join(5000);

        assertThat(holds.calls()).hasSize(1);
        assertThat(results).hasSize(2).hasAtLeastOneElementOfType(ReservationProcess.class)
                .hasAtLeastOneElementOfType(ReservationOpenProcessExistsException.class);
        assertThat(repository.processes()).hasSize(1);
    }

    @Test
    void requestsOfDifferentUsersDoNotWaitForEachOther() throws Exception {
        CyclicBarrier bothRequestingAHold = new CyclicBarrier(2);
        holds.beforeAnswering(() -> {
            try {
                bothRequestingAHold.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("the other user's request never arrived", e);
            }
        });
        List<Object> results = new CopyOnWriteArrayList<>();

        Thread maria = Thread.ofVirtual().start(() -> results.add(attempt(USER, MONDAY, TEN_THIRTY)));
        Thread juan = Thread.ofVirtual().start(() -> results.add(attempt(7L, MONDAY, LocalTime.of(11, 0))));
        maria.join(10000);
        juan.join(10000);

        assertThat(results).hasSize(2).allMatch(ReservationProcess.class::isInstance);
    }

    @Test
    void theUserLocksAreDiscardedWhenNobodyUsesThem() {
        create(MONDAY, TEN_THIRTY);
        assertThatThrownBy(() -> create(MONDAY, LocalTime.of(11, 0))).isInstanceOf(ReservationOpenProcessExistsException.class);

        assertThat((Map<?, ?>) ReflectionTestUtils.getField(userOperationLock, "userLocks")).isEmpty();
    }

    private ReservationProcess create(LocalDate date, LocalTime startTime) {
        return useCase.createReservationProcess(USER, 201L, date, startTime, TOKEN);
    }

    private Object attempt(LocalDate date, LocalTime startTime) {
        return attempt(USER, date, startTime);
    }

    private Object attempt(Long userId, LocalDate date, LocalTime startTime) {
        try {
            return useCase.createReservationProcess(userId, 201L, date, startTime, TOKEN);
        } catch (RuntimeException e) {
            return e;
        }
    }

    private void assertNothingWasCalled() {
        assertThat(professionals.calls()).isEmpty();
        assertThat(holds.calls()).isEmpty();
        assertThat(repository.processes()).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ReservationProcess process(String id, Long userId, ReservationStatus status, Instant expiresAt) {
        return ReservationProcess.builder().reservationProcessId(id).holdId("hold-" + id).userId(userId)
                .professionalId(201L).date(MONDAY).startTime(LocalTime.of(9, 0)).status(status).expiresAt(expiresAt).build();
    }

    // Como la base: rechaza un segundo proceso abierto del mismo usuario
    private static final class InMemoryReservationProcessRepository implements ReservationProcessRepository {

        private final List<ReservationProcess> processes = new CopyOnWriteArrayList<>();
        private final List<String> updates = new CopyOnWriteArrayList<>();
        private volatile RuntimeException failOnCreate;

        ReservationProcess add(ReservationProcess process) {
            processes.add(process);
            return process;
        }

        void failOnCreate(RuntimeException failure) {
            failOnCreate = failure;
        }

        List<ReservationProcess> processes() {
            return processes;
        }

        List<String> updates() {
            return updates;
        }

        @Override
        public synchronized ReservationProcess create(ReservationProcess process) {
            if (failOnCreate != null) {
                throw failOnCreate;
            }
            if (processes.stream().anyMatch(p -> p.getUserId().equals(process.getUserId()) && p.isOpen())) {
                throw new ReservationOpenProcessConflictException(process.getUserId());
            }
            processes.add(process);
            return process;
        }

        @Override
        public Optional<ReservationProcess> update(String reservationProcessId, ReservationProcess process) {
            updates.add(reservationProcessId);
            return Optional.of(process);
        }

        @Override
        public List<ReservationProcess> findOpenByUserId(Long userId) {
            return new ArrayList<>(processes.stream().filter(p -> p.getUserId().equals(userId) && p.isOpen()).toList());
        }

        @Override
        public Optional<ReservationProcess> findByIdAndUserId(String reservationProcessId, Long userId) {
            return processes.stream().filter(p -> p.getReservationProcessId().equals(reservationProcessId)
                    && p.getUserId().equals(userId)).findFirst();
        }

        @Override
        public void markForReconciliation(String reservationProcessId) {
            processes.stream().filter(p -> p.getReservationProcessId().equals(reservationProcessId))
                    .forEach(ReservationProcess::markForReconciliation);
        }
    }

    // Por defecto devuelve el profesional de referencia; guarda cada llamada
    private static final class ScriptedProfessionalProvider implements ProfessionalProvider {

        private final List<String> calls = new CopyOnWriteArrayList<>();
        private volatile Supplier<ReservationProfessional> answer = () -> PROFESSIONAL;

        void then(Supplier<ReservationProfessional> next) {
            answer = next;
        }

        List<String> calls() {
            return calls;
        }

        @Override
        public ReservationProfessional getProfessional(Long professionalId, String userToken) {
            calls.add(professionalId + " " + userToken);
            return answer.get();
        }
    }

    // Por defecto concede el hold process-1; guarda cada llamada y puede hacer algo antes de responder
    private static final class ScriptedHoldProvider implements HoldProvider {

        private final List<String> calls = new CopyOnWriteArrayList<>();
        private volatile Supplier<ReservationHold> answer = () -> ReservationHold.builder().holdId("hold-1")
                .reservationProcessId("process-1").expiresAt(HOLD_EXPIRES_AT).build();
        private volatile Runnable beforeAnswering = () -> { };

        void then(Supplier<ReservationHold> next) {
            answer = next;
        }

        void beforeAnswering(Runnable action) {
            beforeAnswering = action;
        }

        List<String> calls() {
            return calls;
        }

        @Override
        public ReservationHold requestHold(Long professionalId, LocalDate date, LocalTime startTime, String externalPatientId) {
            calls.add(professionalId + " " + date + " " + startTime + " " + externalPatientId);
            beforeAnswering.run();
            ReservationHold hold = answer.get();
            // Cada usuario recibe su propio hold, como en la cátedra
            return ReservationHold.builder().holdId(hold.getHoldId() + (externalPatientId.equals("42") ? "" : "-" + externalPatientId))
                    .reservationProcessId(hold.getReservationProcessId() + (externalPatientId.equals("42") ? "" : "-" + externalPatientId))
                    .expiresAt(hold.getExpiresAt()).build();
        }

        @Override
        public ReservationHoldConfirmation confirmHold(String holdId, Long professionalId, String externalPatientId,
                                                       ReservationPatient patient) {
            throw new UnsupportedOperationException("Blocking a slot never confirms it");
        }
    }

    // Bloquear solo pregunta si el usuario existe
    private record KnownUsers(Set<Long> ids) implements UserDirectory {

        @Override
        public boolean exists(Long userId) {
            return ids.contains(userId);
        }

        @Override
        public Optional<ReservationPatient> findPatient(Long userId) {
            throw new UnsupportedOperationException("Blocking a slot never asks for the patient");
        }
    }
}
