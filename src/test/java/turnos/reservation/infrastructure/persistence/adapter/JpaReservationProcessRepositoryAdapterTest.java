package turnos.reservation.infrastructure.persistence.adapter;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import turnos.TestcontainersConfiguration;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.exception.ReservationOpenProcessConflictException;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({TestcontainersConfiguration.class, JpaReservationProcessRepositoryAdapter.class, ReservationProcessMapper.class})
class JpaReservationProcessRepositoryAdapterTest {

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

    // Así quedan los procesos que ya estaban en la base cuando se agregó la marca
    @Test
    void aProcessSavedWithoutTheMarkIsNotMarked() {
        Long userId = newUser("maria.gomez");
        entityManager.getEntityManager().createNativeQuery("""
                        INSERT INTO reservation_processes (reservation_process_id, hold_id, user_id, professional_id,
                                                           appointment_date, start_time, status, expires_at)
                        VALUES ('process-old', 'hold-old', :userId, 201, DATE '2026-11-02', TIME '12:00', 'HELD', now())
                        """)
                .setParameter("userId", userId).executeUpdate();

        assertThat(repository.findByIdAndUserId("process-old", userId).orElseThrow().isNeedsReconciliation()).isFalse();
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
