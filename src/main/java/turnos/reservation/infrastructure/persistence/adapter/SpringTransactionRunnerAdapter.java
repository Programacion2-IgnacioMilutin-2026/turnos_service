package turnos.reservation.infrastructure.persistence.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.domain.ports.out.TransactionRunner;

// Las escrituras de los repositorios se suman a esta transacción: si cualquier parte del trabajo falla, se deshacen
// todas. Una falla de la base, también al confirmar la transacción, llega como ReservationNotSavedException
@Component
@RequiredArgsConstructor
public class SpringTransactionRunnerAdapter implements TransactionRunner {

    private final PlatformTransactionManager transactionManager;

    @Override
    public void inTransaction(Runnable work) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
        } catch (DataAccessException | TransactionException e) {
            throw new ReservationNotSavedException(e);
        }
    }
}
