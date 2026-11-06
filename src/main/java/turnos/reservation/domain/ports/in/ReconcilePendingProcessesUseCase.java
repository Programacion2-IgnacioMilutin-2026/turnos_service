package turnos.reservation.domain.ports.in;

public interface ReconcilePendingProcessesUseCase {
    // Sin resultado: nadie lo espera, y lo que deja es lo guardado y el log
    void reconcilePendingProcesses();
}
