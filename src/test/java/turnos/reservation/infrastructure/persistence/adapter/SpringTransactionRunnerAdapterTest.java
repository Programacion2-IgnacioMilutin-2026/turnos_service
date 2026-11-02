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
import turnos.reservation.domain.model.ProcessedEvent;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.ProcessedEventRepository;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.domain.ports.out.TransactionRunner;
import turnos.reservation.infrastructure.persistence.mapper.ProcessedEventMapper;
import turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Sin la transacción que cada test de @DataJpaTest abre por defecto: si no, todo quedaría dentro de ella y no se
// vería qué guarda o deshace la transacción del adaptador
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TestcontainersConfiguration.class, SpringTransactionRunnerAdapter.class,
        JpaReservationProcessRepositoryAdapter.class, ReservationProcessMapper.class,
        JpaProcessedEventRepositoryAdapter.class, ProcessedEventMapper.class})
class SpringTransactionRunnerAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Autowired
    private TransactionRunner transactionRunner;

    @Autowired
    private ReservationProcessRepository processRepository;

    @Autowired
    private ProcessedEventRepository eventRepository;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private ReservationProcess process;

    @BeforeEach
    void createHeldProcess() {
        jdbc = new JdbcTemplate(dataSource);
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (login, password_hash, first_name, last_name, email, lang_key, activated,
                                   created_date, last_modified_date)
                VALUES ('maria.gomez', 'hash', 'María', 'Gómez', 'maria@example.com', 'es', true, now(), now())
                RETURNING id
                """, Long.class);
        process = processRepository.create(ReservationProcess.builder().reservationProcessId("process-1")
                .holdId("hold-1").userId(userId).professionalId(201L).date(LocalDate.of(2026, 11, 2))
                .startTime(LocalTime.of(12, 0)).status(ReservationStatus.HELD).expiresAt(NOW).build());
    }

    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM reservation_processes");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void theProcessChangeAndTheEventAreSavedTogether() {
        process.acceptPhoneRequest("request-event-1", NOW.plusSeconds(5));

        transactionRunner.inTransaction(() -> {
            processRepository.update("process-1", process);
            eventRepository.create(new ProcessedEvent("request-event-1", NOW));
        });

        assertThat(storedRequestEventId()).isEqualTo("request-event-1");
        assertThat(eventRepository.existsById("request-event-1")).isTrue();
    }

    @Test
    void ifTheEventCannotBeRegisteredTheProcessChangeIsUndone() {
        eventRepository.create(new ProcessedEvent("request-event-1", NOW));
        process.acceptPhoneRequest("request-event-1", NOW.plusSeconds(5));

        assertThatThrownBy(() -> transactionRunner.inTransaction(() -> {
            processRepository.update("process-1", process);
            eventRepository.create(new ProcessedEvent("request-event-1", NOW));
        })).isInstanceOf(ReservationNotSavedException.class);

        assertThat(storedRequestEventId()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_processes", String.class)).isEqualTo("HELD");
    }

    @Test
    void ifTheProcessCannotBeSavedTheEventIsNotRegistered() {
        // Sin estado, la base rechaza el proceso
        process.setStatus(null);

        assertThatThrownBy(() -> transactionRunner.inTransaction(() -> {
            eventRepository.create(new ProcessedEvent("request-event-1", NOW));
            processRepository.update("process-1", process);
        })).isInstanceOf(ReservationNotSavedException.class);

        assertThat(eventRepository.existsById("request-event-1")).isFalse();
    }

    @Test
    void anErrorThatIsNotOfTheDatabaseAlsoUndoesEverythingAndArrivesAsIs() {
        IllegalStateException failure = new IllegalStateException("unexpected");

        assertThatThrownBy(() -> transactionRunner.inTransaction(() -> {
            eventRepository.create(new ProcessedEvent("request-event-1", NOW));
            throw failure;
        })).isSameAs(failure);

        assertThat(eventRepository.existsById("request-event-1")).isFalse();
    }

    private String storedRequestEventId() {
        return jdbc.queryForObject("SELECT request_event_id FROM reservation_processes WHERE reservation_process_id = 'process-1'",
                String.class);
    }
}
