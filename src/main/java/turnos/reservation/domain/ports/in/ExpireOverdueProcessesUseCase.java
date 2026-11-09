package turnos.reservation.domain.ports.in;

public interface ExpireOverdueProcessesUseCase {
    // Sin resultado: nadie lo espera, y lo que deja es lo guardado y una línea en el log
    void expireOverdueProcesses();
}
