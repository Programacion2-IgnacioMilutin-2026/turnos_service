package turnos.reservation.infrastructure.persistence.adapter;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import turnos.TestcontainersConfiguration;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.exception.ReservationOpenProcessConflictException;
import turnos.reservation.domain.model.ReservationListCriteria;
import turnos.reservation.domain.model.ReservationPhoneNumber;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import turnos.reservation.infrastructure.persistence.repository.JpaReservationProcessRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({TestcontainersConfiguration.class, JpaReservationProcessRepositoryAdapter.class, ReservationProcessMapper.class})
class JpaReservationProcessRepositoryAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-03T13:00:00Z");
    private static final Duration MARGIN = Duration.ofSeconds(30);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    private static final LocalTime TIME = LocalTime.of(10, 0);

    @Autowired
    private ReservationProcessRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void aProcessIsSavedAndReadBackWithEveryField() {
        Long userId = newUser("maria.gomez");
        ReservationProcess original = held("process-1", userId);
        repository.create(original);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findOpenByUserId(userId)).singleElement().usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void savingANewProcessDoesNotQueryBeforeInserting() {
        Long userId = newUser("maria.gomez");
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        repository.create(held("process-1", userId));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void onlyTheOpenProcessesOfThatUserAreFound() {
        Long maria = newUser("maria.gomez");
        Long juan = newUser("juan.perez");
        repository.create(held("process-maria", maria));
        repository.create(held("process-juan", juan));

        assertThat(repository.findOpenByUserId(maria)).extracting(ReservationProcess::getReservationProcessId)
                .containsExactly("process-maria");
    }

    @Test
    void updatingWithoutAProcessFailsAndLeavesTheSavedOneAsItWas() {
        Long userId = newUser("maria.gomez");
        ReservationProcess original = repository.create(held("process-1", userId));
        entityManager.clear();

        assertThatThrownBy(() -> repository.update("process-1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("reservationProcess must not be null");
        entityManager.clear();
        assertThat(repository.findOpenByUserId(userId)).singleElement().usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void anExpiredProcessDoesNotPreventANewOpenOne() {
        Long userId = newUser("maria.gomez");
        ReservationProcess first = repository.create(held("process-1", userId));
        first.setStatus(ReservationStatus.EXPIRED);

        assertThat(repository.update("process-1", first)).isPresent();
        repository.create(held("process-2", userId));

        assertThat(repository.findOpenByUserId(userId)).extracting(ReservationProcess::getReservationProcessId)
                .containsExactly("process-2");
    }

    @Test
    void updatingAMissingProcessFindsNothing() {
        assertThat(repository.update("process-missing", held("process-missing", 1L))).isEmpty();
    }

    @Test
    void theDatabaseRejectsASecondOpenProcessOfTheSameUser() {
        Long userId = newUser("maria.gomez");
        repository.create(held("process-1", userId));

        assertThatThrownBy(() -> repository.create(held("process-2", userId)))
                .isInstanceOf(ReservationOpenProcessConflictException.class);
    }

    @Test
    void aProcessIsFoundOnlyForItsOwner() {
        Long maria = newUser("maria.gomez");
        Long juan = newUser("juan.perez");
        ReservationProcess original = repository.create(held("process-1", maria));
        entityManager.clear();

        assertThat(repository.findByIdAndUserId("process-1", maria)).get().usingRecursiveComparison().isEqualTo(original);
        assertThat(repository.findByIdAndUserId("process-1", juan)).isEmpty();
        assertThat(repository.findByIdAndUserId("process-missing", maria)).isEmpty();
    }

    @Test
    void aProcessIsFoundByItsIdWithoutCheckingTheOwner() {
        Long maria = newUser("maria.gomez");
        ReservationProcess original = repository.create(held("process-1", maria));
        entityManager.clear();

        assertThat(repository.findById("process-1")).get().usingRecursiveComparison().isEqualTo(original);
        assertThat(repository.findById("process-missing")).isEmpty();
    }

    @Test
    void thePhoneRequestIsSavedAndReadBack() {
        Long userId = newUser("maria.gomez");
        ReservationProcess process = repository.create(held("process-1", userId));
        process.acceptPhoneRequest("request-event-1", Instant.parse("2026-10-27T04:45:00Z"));

        repository.update("process-1", process);
        entityManager.clear();

        ReservationProcess read = repository.findById("process-1").orElseThrow();
        assertThat(read.getRequestEventId()).isEqualTo("request-event-1");
        assertThat(read.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(read.getExpiresAt()).isEqualTo(Instant.parse("2026-10-27T04:45:00Z"));
    }

    @Test
    void markingForReconciliationChangesOnlyTheMark() {
        Long userId = newUser("maria.gomez");
        ReservationProcess original = repository.create(held("process-1", userId));
        entityManager.clear();

        repository.markForReconciliation("process-1");
        entityManager.clear();

        ReservationProcess marked = repository.findByIdAndUserId("process-1", userId).orElseThrow();
        assertThat(marked.isNeedsReconciliation()).isTrue();
        assertThat(marked).usingRecursiveComparison().ignoringFields("needsReconciliation").isEqualTo(original);
    }

    @Test
    void markingAMissingProcessChangesNothing() {
        repository.markForReconciliation("process-missing");

        assertThat(entityManager.getEntityManager().createNativeQuery("SELECT count(*) FROM reservation_processes")
                .getSingleResult()).isEqualTo(0L);
    }

    @Test
    void aMarkSavedWithTheWholeProcessIsReadBack() {
        Long userId = newUser("maria.gomez");
        ReservationProcess process = repository.create(held("process-1", userId));
        process.markForReconciliation();

        repository.update("process-1", process);
        entityManager.clear();

        ReservationProcess read = repository.findByIdAndUserId("process-1", userId).orElseThrow();
        assertThat(read.isNeedsReconciliation()).isTrue();
        assertThat(read.getStatus()).isEqualTo(ReservationStatus.HELD);
    }

    // Así quedan los procesos que ya estaban en la base cuando se agregaron la marca y el pedido de teléfono
    @Test
    void aProcessSavedWithoutTheMarkIsNotMarked() {
        Long userId = newUser("maria.gomez");
        entityManager.getEntityManager().createNativeQuery("""
                        INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                                                           appointment_date, start_time, status, expires_at)
                        VALUES ('process-old', 'hold-old', :userId, 201, DATE '2026-11-02', TIME '12:00', 'HELD', now())
                        """)
                .setParameter("userId", userId).executeUpdate();

        ReservationProcess old = repository.findByIdAndUserId("process-old", userId).orElseThrow();
        assertThat(old.isNeedsReconciliation()).isFalse();
        assertThat(old.getRequestEventId()).isNull();
        assertThat(old.getReservationId()).isNull();
        assertThat(old.getConfirmedAt()).isNull();
        assertThat(old.getCancelledAt()).isNull();
        assertThat(old.getCancellationReason()).isNull();
        assertThat(old.getRejectionReason()).isNull();
    }

    @Test
    void theResultOfAProcessIsSavedAndReadBack() {
        Long userId = newUser("maria.gomez");
        ReservationProcess process = repository.create(held("process-1", userId));
        process.setStatus(ReservationStatus.CANCELLED);
        process.setReservationId(8251L);
        process.setConfirmedAt(Instant.parse("2026-10-27T04:36:01Z"));
        process.setCancelledAt(Instant.parse("2026-10-28T15:10:00Z"));
        process.setCancellationReason("a".repeat(ReservationProcess.MAX_REASON_LENGTH));
        process.setRejectionReason("é".repeat(ReservationProcess.MAX_REASON_LENGTH));

        repository.update("process-1", process);
        entityManager.clear();

        assertThat(repository.findById("process-1").orElseThrow()).usingRecursiveComparison().isEqualTo(process);
    }

    @Test
    void theLongestValidPhoneIsSavedAndReadBack() {
        Long userId = newUser("maria.gomez");
        ReservationProcess process = repository.create(held("process-1", userId));
        process.setStatus(ReservationStatus.WAITING_FOR_PHONE);
        process.submitPhone(ReservationPhoneNumber.parse("+123456789012345").orElseThrow());

        repository.update("process-1", process);
        entityManager.clear();

        ReservationProcess saved = repository.findById("process-1").orElseThrow();
        assertThat(saved.getPhoneNumber()).isEqualTo("+123456789012345");
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
    }

    @Test
    void aProcessThatNeverReceivedAPhoneHasNone() {
        Long userId = newUser("maria.gomez");
        repository.create(held("process-1", userId));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findById("process-1").orElseThrow().getPhoneNumber()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CANCELLED", "INVALID"})
    void aClosedProcessIsNotOpenAndLetsItsUserHaveAnother(ReservationStatus status) {
        Long userId = newUser("maria.gomez");
        ReservationProcess closed = repository.create(held("process-1", userId));
        closed.setStatus(status);
        repository.update("process-1", closed);

        ReservationProcess another = held("process-2", userId);
        another.setHoldId("hold-2");
        repository.create(another);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findById("process-1").orElseThrow().getStatus()).isEqualTo(status);
        assertThat(repository.findOpenByUserId(userId)).extracting(ReservationProcess::getReservationProcessId)
                .containsExactly("process-2");
    }

    @Test
    void aProcessOfAUserThatDoesNotExistIsNotSaved() {
        assertThatThrownBy(() -> repository.create(held("process-1", 999_999L)))
                .isInstanceOf(ReservationNotSavedException.class)
                .hasStackTraceContaining("fk_reservation_processes_user");
    }

    @Test
    void aUserWithProcessesCannotBeDeleted() {
        Long userId = newUser("maria.gomez");
        repository.create(held("process-1", userId));

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", userId).executeUpdate())
                .hasStackTraceContaining("fk_reservation_processes_user");
    }

    private Long newUser(String login) {
        Number id = (Number) entityManager.getEntityManager().createNativeQuery("""
                        INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated,
                                           created_date, last_modified_date)
                        VALUES (:login, 'hash', 'María', 'Gómez', :email, 'es', true, now(), now())
                        RETURNING id
                        """)
                .setParameter("login", login).setParameter("email", login + "@example.com").getSingleResult();
        return id.longValue();
    }

    @Test
    void theOpenProcessGoesFirstThenTheUpcomingSlotsThenThePastOnes() {
        Long userId = newUser("maria.gomez");
        listed("past-oldest", userId, ReservationStatus.CANCELLED, DAY.minusDays(30), LocalTime.of(9, 0));
        listed("upcoming-far", userId, ReservationStatus.CONFIRMED, DAY.plusDays(30), LocalTime.of(9, 0));
        listed("today-started", userId, ReservationStatus.CONFIRMED, DAY, TIME.minusMinutes(1));
        listed("past-recent-late", userId, ReservationStatus.CONFIRMED, DAY.minusDays(1), LocalTime.of(18, 0));
        listed("upcoming-near-late", userId, ReservationStatus.CONFIRMED, DAY.plusDays(2), LocalTime.of(15, 0));
        listed("today-now", userId, ReservationStatus.CONFIRMED, DAY, TIME);
        listed("past-recent-early", userId, ReservationStatus.CONFIRMED, DAY.minusDays(1), LocalTime.of(8, 0));
        listed("upcoming-near-early", userId, ReservationStatus.CANCELLED, DAY.plusDays(2), LocalTime.of(8, 0));
        // Abierto y con un turno viejo: igual va primero
        listed("open", userId, ReservationStatus.PHONE_SUBMITTED, DAY.minusDays(60), LocalTime.of(9, 0));

        assertThat(ids(repository.findListable(criteria(userId, null, 0, 100)))).containsExactly("open", "today-now",
                "upcoming-near-early", "upcoming-near-late", "upcoming-far", "today-started", "past-recent-late",
                "past-recent-early", "past-oldest");
    }

    @Test
    void theSameDateAndTimeAreOrderedByIdInBothGroups() {
        Long userId = newUser("maria.gomez");
        for (String id : List.of("z", "a", "m")) {
            listed("upcoming-" + id, userId, ReservationStatus.CONFIRMED, DAY.plusDays(1), TIME);
            listed("past-" + id, userId, ReservationStatus.CANCELLED, DAY.minusDays(1), TIME);
        }

        assertThat(ids(repository.findListable(criteria(userId, null, 0, 100)))).containsExactly("upcoming-a", "upcoming-m",
                "upcoming-z", "past-a", "past-m", "past-z");
    }

    @Test
    void walkingThePagesNeverRepeatsOrMissesAProcess() {
        Long userId = newUser("maria.gomez");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String id = "tied-" + i;
            listed(id, userId, ReservationStatus.CONFIRMED, DAY.plusDays(1), TIME);
            expected.add(id);
        }

        List<String> walked = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            ReservationProcessPage result = repository.findListable(criteria(userId, null, page, 3));
            assertThat(result.totalItems()).isEqualTo(7);
            walked.addAll(ids(result));
        }

        assertThat(walked).isEqualTo(expected);
    }

    @Test
    void aPageBeyondTheLastIsEmptyWithTheRealTotal() {
        Long userId = newUser("maria.gomez");
        listed("process-1", userId, ReservationStatus.CONFIRMED, DAY, TIME);

        ReservationProcessPage result = repository.findListable(criteria(userId, null, 1, 10));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalItems()).isEqualTo(1);
    }

    @Test
    void anEnormousPageNumberIsJustAnEmptyPage() {
        Long userId = newUser("maria.gomez");
        listed("process-1", userId, ReservationStatus.CONFIRMED, DAY, TIME);

        ReservationProcessPage result = repository.findListable(criteria(userId, null, Integer.MAX_VALUE, 100));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalItems()).isEqualTo(1);
    }

    @Test
    void aUserWithoutReservationsGetsAnEmptyPage() {
        ReservationProcessPage result = repository.findListable(criteria(newUser("maria.gomez"), null, 0, 10));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalItems()).isZero();
    }

    @Test
    void onlyTheProcessesOfThatUserAreListedAndCounted() {
        Long maria = newUser("maria.gomez");
        Long juan = newUser("juan.perez");
        listed("process-maria", maria, ReservationStatus.CONFIRMED, DAY, TIME);
        listed("process-juan", juan, ReservationStatus.CONFIRMED, DAY, TIME);

        ReservationProcessPage result = repository.findListable(criteria(maria, null, 0, 10));

        assertThat(ids(result)).containsExactly("process-maria");
        assertThat(result.totalItems()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE", "PHONE_SUBMITTED", "CONFIRMED", "CANCELLED"})
    void theFilterKeepsOnlyThatStatus(ReservationStatus status) {
        Long userId = newUser("maria.gomez");
        listed("process-open", userId, status.isOpen() ? status : ReservationStatus.HELD, DAY, TIME);
        listed("process-confirmed", userId, ReservationStatus.CONFIRMED, DAY, TIME);
        listed("process-cancelled", userId, ReservationStatus.CANCELLED, DAY, TIME);

        ReservationProcessPage result = repository.findListable(criteria(userId, status, 0, 10));

        assertThat(result.items()).extracting(ReservationProcess::getStatus).containsOnly(status);
        assertThat(result.totalItems()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"EXPIRED", "INVALID"})
    void anAttemptThatCameToNothingIsNotListed(ReservationStatus status) {
        Long userId = newUser("maria.gomez");
        listed("process-1", userId, status, DAY, TIME);

        ReservationProcessPage result = repository.findListable(criteria(userId, null, 0, 10));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalItems()).isZero();
    }

    @Test
    void aProcessCancelledBeforeConfirmationIsNotListedNotEvenFilteringByCancelled() {
        Long userId = newUser("maria.gomez");
        ReservationProcess neverConfirmed = process("never-confirmed", userId, ReservationStatus.CANCELLED, DAY, TIME);
        neverConfirmed.setConfirmedAt(null);
        repository.create(neverConfirmed);
        listed("confirmed-first", userId, ReservationStatus.CANCELLED, DAY, TIME);

        assertThat(ids(repository.findListable(criteria(userId, null, 0, 10)))).containsExactly("confirmed-first");
        ReservationProcessPage filtered = repository.findListable(criteria(userId, ReservationStatus.CANCELLED, 0, 10));
        assertThat(ids(filtered)).containsExactly("confirmed-first");
        assertThat(filtered.totalItems()).isEqualTo(1);
    }

    // La regla del vencido está en el modelo y en la consulta: las dos tienen que decir lo mismo, también en el límite
    @ParameterizedTest
    @CsvSource({"HELD, -1", "HELD, 0", "HELD, 1", "WAITING_FOR_PHONE, -1", "WAITING_FOR_PHONE, 0", "WAITING_FOR_PHONE, 1"})
    void aProcessWaitingForTheUserIsListedExactlyUntilTheModelSaysItExpired(ReservationStatus status, long secondsFromTheLimit) {
        Long userId = newUser("maria.gomez");
        ReservationProcess process = process("process-1", userId, status, DAY, TIME);
        process.setExpiresAt(NOW.minus(MARGIN).plusSeconds(secondsFromTheLimit));
        repository.create(process);
        boolean expired = process.hasExpired(NOW, MARGIN);

        assertThat(expired).isEqualTo(secondsFromTheLimit < 0);
        assertThat(repository.findListable(criteria(userId, null, 0, 10)).items().isEmpty()).isEqualTo(expired);
        assertThat(repository.findListable(criteria(userId, status, 0, 10)).totalItems()).isEqualTo(expired ? 0 : 1);
    }

    // La regla del vencido está en el modelo y en esta búsqueda: las dos tienen que decir lo mismo, también en el límite
    @ParameterizedTest
    @CsvSource({"HELD, -1", "HELD, 0", "HELD, 1", "WAITING_FOR_PHONE, -1", "WAITING_FOR_PHONE, 0", "WAITING_FOR_PHONE, 1",
            "PHONE_SUBMITTED, -1", "PHONE_SUBMITTED, 0", "PHONE_SUBMITTED, 1"})
    void anOpenProcessIsFoundAsOverdueExactlyWhenTheModelSaysSo(ReservationStatus status, long secondsFromTheLimit) {
        Long userId = newUser("maria.gomez");
        ReservationProcess process = process("process-1", userId, status, DAY, TIME);
        process.setExpiresAt(NOW.minus(MARGIN).plusSeconds(secondsFromTheLimit));
        repository.create(process);
        boolean overdue = process.needsOverdueResolution(NOW, MARGIN);

        assertThat(overdue).isEqualTo(secondsFromTheLimit < 0);
        assertThat(repository.findOverdue(NOW.minus(MARGIN)).isEmpty()).isEqualTo(!overdue);
    }

    @ParameterizedTest
    @CsvSource({"HELD, true", "WAITING_FOR_PHONE, true", "PHONE_SUBMITTED, false"})
    void aMarkedProcessIsFoundAsOverdueOnlyIfTheModelStillHasSomethingToDo(ReservationStatus status, boolean found) {
        Long userId = newUser("maria.gomez");
        ReservationProcess process = process("process-1", userId, status, DAY, TIME);
        process.setExpiresAt(NOW.minusSeconds(3600));
        process.setNeedsReconciliation(true);
        repository.create(process);

        assertThat(process.needsOverdueResolution(NOW, MARGIN)).isEqualTo(found);
        assertThat(repository.findOverdue(NOW.minus(MARGIN)).isEmpty()).isEqualTo(!found);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CANCELLED", "EXPIRED", "INVALID"})
    void aClosedProcessIsNotFoundAsOverdueNoMatterHowOldItIs(ReservationStatus status) {
        ReservationProcess process = process("process-1", newUser("maria.gomez"), status, DAY, TIME);
        process.setExpiresAt(NOW.minusSeconds(3600));
        repository.create(process);

        assertThat(repository.findOverdue(NOW.minus(MARGIN))).isEmpty();
    }

    @Test
    void overdueProcessesOfEveryUserComeOldestFirstWithEveryField() {
        ReservationProcess newest = process("process-a", newUser("maria.gomez"), ReservationStatus.HELD, DAY, TIME);
        newest.setExpiresAt(NOW.minusSeconds(100));
        ReservationProcess oldest = process("process-b", newUser("juan.perez"), ReservationStatus.PHONE_SUBMITTED, DAY, TIME);
        oldest.setExpiresAt(NOW.minusSeconds(300));
        ReservationProcess tiedWithNewest = process("process-c", newUser("ana.diaz"), ReservationStatus.WAITING_FOR_PHONE, DAY, TIME);
        tiedWithNewest.setExpiresAt(NOW.minusSeconds(100));
        repository.create(newest);
        repository.create(oldest);
        repository.create(tiedWithNewest);
        entityManager.flush();
        entityManager.clear();

        List<ReservationProcess> found = repository.findOverdue(NOW.minus(MARGIN));

        assertThat(found).extracting(ReservationProcess::getReservationProcessId).containsExactly("process-b", "process-a", "process-c");
        assertThat(found.getFirst()).usingRecursiveComparison().isEqualTo(oldest);
    }

    @Test
    void aFailureSearchingForOverdueProcessesIsReportedAsNotSaved() {
        JpaReservationProcessRepository failing = mock(JpaReservationProcessRepository.class);
        when(failing.findOverdue(any(), any(), any())).thenThrow(new DataAccessResourceFailureException("database down"));
        ReservationProcessRepository adapter = new JpaReservationProcessRepositoryAdapter(failing, new ReservationProcessMapper());

        assertThatThrownBy(() -> adapter.findOverdue(NOW)).isInstanceOf(ReservationNotSavedException.class);
    }

    @Test
    void aProcessWithThePhoneSubmittedIsListedEvenIfItExpired() {
        Long userId = newUser("maria.gomez");
        ReservationProcess process = process("process-1", userId, ReservationStatus.PHONE_SUBMITTED, DAY, TIME);
        process.setExpiresAt(NOW.minusSeconds(3600));
        repository.create(process);

        assertThat(ids(repository.findListable(criteria(userId, null, 0, 10)))).containsExactly("process-1");
    }

    @Test
    void anOldExpirationDoesNotHideAProcessThatEnded() {
        Long userId = newUser("maria.gomez");
        ReservationProcess process = process("process-1", userId, ReservationStatus.CONFIRMED, DAY, TIME);
        process.setExpiresAt(NOW.minusSeconds(3600));
        repository.create(process);

        assertThat(ids(repository.findListable(criteria(userId, null, 0, 10)))).containsExactly("process-1");
    }

    @Test
    void listingChangesNothingAndBringsEveryField() {
        Long userId = newUser("maria.gomez");
        ReservationProcess original = process("process-1", userId, ReservationStatus.CONFIRMED, DAY, TIME);
        repository.create(original);
        entityManager.flush();
        entityManager.clear();

        ReservationProcess found = repository.findListable(criteria(userId, null, 0, 10)).items().getFirst();

        assertThat(found).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void theDatabaseHasAnIndexToFindTheProcessesOfAUser() {
        List<?> indexes = entityManager.getEntityManager().createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'reservation_processes' AND indexname = 'ix_reservation_processes_user'
                """).getResultList();

        assertThat(indexes).singleElement().asString().contains("(user_id)").doesNotContain("WHERE");
    }

    private void listed(String reservationProcessId, Long userId, ReservationStatus status, LocalDate date, LocalTime startTime) {
        repository.create(process(reservationProcessId, userId, status, date, startTime));
    }

    private static ReservationProcess process(String reservationProcessId, Long userId, ReservationStatus status,
                                              LocalDate date, LocalTime startTime) {
        ReservationProcess process = held(reservationProcessId, userId);
        process.setStatus(status);
        process.setDate(date);
        process.setStartTime(startTime);
        process.setExpiresAt(NOW.plusSeconds(600));
        process.setConfirmedAt(Instant.parse("2026-10-27T04:36:01Z"));
        return process;
    }

    private static ReservationListCriteria criteria(Long userId, ReservationStatus status, int page, int size) {
        return new ReservationListCriteria(userId, status, NOW.minus(MARGIN), DAY, TIME, page, size);
    }

    private static List<String> ids(ReservationProcessPage page) {
        return page.items().stream().map(ReservationProcess::getReservationProcessId).toList();
    }

    private static ReservationProcess held(String reservationProcessId, Long userId) {
        return ReservationProcess.builder()
                .reservationProcessId(reservationProcessId)
                .holdId("hold-" + reservationProcessId)
                .userId(userId)
                .professionalId(201L)
                .professionalFirstName("Ana")
                .professionalLastName("Gomez")
                .professionalCategoryName("Clinica medica")
                .date(LocalDate.of(2026, 11, 2))
                .startTime(LocalTime.of(12, 0))
                .status(ReservationStatus.HELD)
                .expiresAt(Instant.parse("2026-10-27T04:44:38Z"))
                .build();
    }
}
