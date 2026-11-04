package turnos.reservation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import turnos.reservation.domain.model.ReservationListCriteria;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.infrastructure.persistence.adapter.JpaReservationProcessRepositoryAdapter;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

// La base real, que falla al guardar cuando el test lo indica, cuenta lo que guarda y avisa al leer por dueño
final class ControllableProcessRepository implements ReservationProcessRepository {

    private static final String DEFAULT_FAILURE = "database down";

    private final ReservationProcessRepository realDatabase;
    private final AtomicInteger updates = new AtomicInteger();
    private volatile Predicate<ReservationProcess> failUpdate = process -> false;
    private volatile String failureMessage = DEFAULT_FAILURE;
    private volatile Runnable afterOwnerRead = () -> { };

    ControllableProcessRepository(ReservationProcessRepository realDatabase) {
        this.realDatabase = realDatabase;
    }

    void failUpdatesWhen(Predicate<ReservationProcess> condition) {
        failUpdate = condition;
    }

    void failUpdatesWith(String message) {
        failureMessage = message;
        failUpdate = process -> true;
    }

    // Lo que pasa después de la próxima lectura por dueño, antes de que el que leyó siga adelante
    void afterNextOwnerRead(Runnable action) {
        afterOwnerRead = action;
    }

    int updates() {
        return updates.get();
    }

    void reset() {
        failUpdate = process -> false;
        failureMessage = DEFAULT_FAILURE;
        afterOwnerRead = () -> { };
        updates.set(0);
    }

    @Override
    public ReservationProcess create(ReservationProcess reservationProcess) {
        return realDatabase.create(reservationProcess);
    }

    @Override
    public Optional<ReservationProcess> update(String reservationProcessId, ReservationProcess reservationProcess) {
        if (failUpdate.test(reservationProcess)) {
            throw new IllegalStateException(failureMessage);
        }
        updates.incrementAndGet();
        return realDatabase.update(reservationProcessId, reservationProcess);
    }

    @Override
    public List<ReservationProcess> findOpenByUserId(Long userId) {
        return realDatabase.findOpenByUserId(userId);
    }

    @Override
    public Optional<ReservationProcess> findById(String reservationProcessId) {
        return realDatabase.findById(reservationProcessId);
    }

    @Override
    public Optional<ReservationProcess> findByIdAndUserId(String reservationProcessId, Long userId) {
        Optional<ReservationProcess> found = realDatabase.findByIdAndUserId(reservationProcessId, userId);
        Runnable action = afterOwnerRead;
        afterOwnerRead = () -> { };
        action.run();
        return found;
    }

    @Override
    public ReservationProcessPage findListable(ReservationListCriteria criteria) {
        return realDatabase.findListable(criteria);
    }

    @Override
    public void markForReconciliation(String reservationProcessId) {
        realDatabase.markForReconciliation(reservationProcessId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        ControllableProcessRepository controllableProcessRepository(JpaReservationProcessRepositoryAdapter realDatabase) {
            return new ControllableProcessRepository(realDatabase);
        }
    }
}
