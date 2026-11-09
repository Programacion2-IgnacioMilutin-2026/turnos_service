package turnos.reservation.infrastructure.persistence.adapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import turnos.TestcontainersConfiguration;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.application.usecases.ProcessReservationResultUseCaseImpl;
import turnos.reservation.domain.model.ProcessedEvent;
import turnos.reservation.domain.model.ReservationEventOutcome;
import turnos.reservation.domain.model.ReservationListCriteria;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationResult;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.ProcessedEventRepository;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.domain.ports.out.TransactionRunner;
import turnos.reservation.infrastructure.persistence.mapper.ProcessedEventMapper;
import turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// El caso de uso contra la base real, sin la transacción que cada test de @DataJpaTest abre por defecto: si no, todo
// quedaría dentro de ella y no se vería qué guarda o deshace la transacción del caso de uso
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TestcontainersConfiguration.class, SpringTransactionRunnerAdapter.class,
        JpaReservationProcessRepositoryAdapter.class, ReservationProcessMapper.class,
        JpaProcessedEventRepositoryAdapter.class, ProcessedEventMapper.class})
class ProcessReservationResultAtomicityTest {

    private static final Instant NOW = Instant.parse("2026-11-02T13:00:00Z");
    private static final ReservationResult CONFIRMED = new ReservationResult.Confirmed("event-1", "process-1", "hold-1",
            8251L, NOW, null, null, null);

    @Autowired
    private TransactionRunner transactionRunner;

    @Autowired
    private ReservationProcessRepository processRepository;

    @Autowired
    private ProcessedEventRepository eventRepository;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void createProcessWithThePhoneSubmitted() {
        jdbc = new JdbcTemplate(dataSource);
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated,
                                   created_date, last_modified_date)
                VALUES ('maria.gomez', 'hash', 'María', 'Gómez', 'maria@example.com', 'es', true, now(), now())
                RETURNING id
                """, Long.class);
        processRepository.create(ReservationProcess.builder().reservationProcessId("process-1").holdId("hold-1")
                .userId(userId).professionalId(201L).date(LocalDate.of(2026, 11, 9)).startTime(LocalTime.of(12, 0))
                .status(ReservationStatus.PHONE_SUBMITTED).expiresAt(NOW).build());
    }

    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM reservation_processes");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void theProcessChangeAndTheEventAreSavedTogether() {
        assertThat(useCase(processRepository, eventRepository).processReservationResult(CONFIRMED))
                .isEqualTo(ReservationEventOutcome.APPLIED);

        assertThat(storedStatus()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT reservation_id FROM reservation_processes", Long.class)).isEqualTo(8251L);
        assertThat(registeredEvents()).containsExactly("event-1");
    }

    @Test
    void ifTheEventCannotBeRegisteredTheProcessChangeIsUndone() {
        eventRepository.create(new ProcessedEvent("event-1", NOW));
        // Dice que el evento no está, para llegar a registrarlo: la clave primaria de la base lo rechaza
        ProcessedEventRepository notFindingIt = new ProcessedEventRepository() {
            @Override
            public ProcessedEvent create(ProcessedEvent processedEvent) {
                return eventRepository.create(processedEvent);
            }

            @Override
            public boolean existsById(String eventId) {
                return false;
            }
        };

        assertThatThrownBy(() -> useCase(processRepository, notFindingIt).processReservationResult(CONFIRMED))
                .isInstanceOf(ReservationNotSavedException.class);

        assertThat(storedStatus()).isEqualTo("PHONE_SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_processes WHERE reservation_id IS NOT NULL",
                Long.class)).isZero();
    }

    @Test
    void ifTheProcessCannotBeSavedTheEventIsNotRegistered() {
        // Guarda el proceso y falla enseguida: lo escrito hasta ahí también se tiene que deshacer
        ReservationProcessRepository failingAfterUpdate = new FailingAfterUpdate(processRepository);

        assertThatThrownBy(() -> useCase(failingAfterUpdate, eventRepository).processReservationResult(CONFIRMED))
                .isInstanceOf(IllegalStateException.class).hasMessage("database down");

        assertThat(storedStatus()).isEqualTo("PHONE_SUBMITTED");
        assertThat(registeredEvents()).isEmpty();
    }

    private ProcessReservationResultUseCaseImpl useCase(ReservationProcessRepository processes, ProcessedEventRepository events) {
        return new ProcessReservationResultUseCaseImpl(processes, events, transactionRunner, new UserOperationLock(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String storedStatus() {
        return jdbc.queryForObject("SELECT status FROM reservation_processes", String.class);
    }

    private List<String> registeredEvents() {
        return jdbc.queryForList("SELECT event_id FROM processed_events", String.class);
    }

    private record FailingAfterUpdate(ReservationProcessRepository delegate) implements ReservationProcessRepository {

        @Override
        public Optional<ReservationProcess> update(String reservationProcessId, ReservationProcess reservationProcess) {
            delegate.update(reservationProcessId, reservationProcess);
            throw new IllegalStateException("database down");
        }

        @Override
        public ReservationProcess create(ReservationProcess reservationProcess) {
            return delegate.create(reservationProcess);
        }

        @Override
        public List<ReservationProcess> findOpenByUserId(Long userId) {
            return delegate.findOpenByUserId(userId);
        }

        @Override
        public Optional<ReservationProcess> findById(String reservationProcessId) {
            return delegate.findById(reservationProcessId);
        }

        @Override
        public Optional<ReservationProcess> findByIdAndUserId(String reservationProcessId, Long userId) {
            return delegate.findByIdAndUserId(reservationProcessId, userId);
        }

        @Override
        public ReservationProcessPage findListable(ReservationListCriteria criteria) {
            return delegate.findListable(criteria);
        }

        @Override
        public List<ReservationProcess> findOverdue(Instant expiredBefore) {
            return delegate.findOverdue(expiredBefore);
        }

        @Override
        public List<ReservationProcess> findToReconcile(boolean includeOpen) {
            return delegate.findToReconcile(includeOpen);
        }

        @Override
        public void markForReconciliation(String reservationProcessId) {
            delegate.markForReconciliation(reservationProcessId);
        }
    }
}
