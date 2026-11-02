package turnos.reservation.domain.ports.out;

// Puerto técnico, no un repositorio: excepción a la skill autorizada por el usuario.
// Ejecuta un trabajo de forma atómica: las escrituras que hace se guardan todas o ninguna. Si la base falla, lanza
// ReservationNotSavedException
public interface TransactionRunner {

    void inTransaction(Runnable work);

}
