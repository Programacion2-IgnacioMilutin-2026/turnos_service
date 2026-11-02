package turnos.reservation.infrastructure.persistence.adapter;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.infrastructure.persistence.mapper.ProcessedEventMapper;
import turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import turnos.reservation.infrastructure.persistence.repository.JpaProcessedEventRepository;
import turnos.reservation.infrastructure.persistence.repository.JpaReservationProcessRepository;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Las dos formas en que llega una base caída a una lectura: sin ellas, el evento de la cátedra se descartaría en lugar
// de reintentarse. La prueba con Postgres caído de verdad está en DatabaseDownKafkaIntegrationTest
class CatedraEventReadFailureTest {

    private static Stream<RuntimeException> databaseFailures() {
        return Stream.of(new DataAccessResourceFailureException("database down"),
                new CannotCreateTransactionException("database down"));
    }

    @ParameterizedTest
    @MethodSource("databaseFailures")
    void aFailureReadingAProcessArrivesAsNotSaved(RuntimeException failure) {
        JpaReservationProcessRepository repository = mock(JpaReservationProcessRepository.class);
        when(repository.findById("process-1")).thenThrow(failure);
        JpaReservationProcessRepositoryAdapter adapter =
                new JpaReservationProcessRepositoryAdapter(repository, new ReservationProcessMapper());

        assertThatThrownBy(() -> adapter.findById("process-1"))
                .isInstanceOf(ReservationNotSavedException.class).hasCause(failure);
    }

    @ParameterizedTest
    @MethodSource("databaseFailures")
    void aFailureCheckingAnEventArrivesAsNotSaved(RuntimeException failure) {
        JpaProcessedEventRepository repository = mock(JpaProcessedEventRepository.class);
        when(repository.existsById("event-1")).thenThrow(failure);
        JpaProcessedEventRepositoryAdapter adapter =
                new JpaProcessedEventRepositoryAdapter(repository, new ProcessedEventMapper());

        assertThatThrownBy(() -> adapter.existsById("event-1"))
                .isInstanceOf(ReservationNotSavedException.class).hasCause(failure);
    }
}
