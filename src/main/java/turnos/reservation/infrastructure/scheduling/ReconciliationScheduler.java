package turnos.reservation.infrastructure.scheduling;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import turnos.reservation.application.service.ReservationService;

// Paquete infrastructure/scheduling: excepción a la skill autorizada por el usuario.
// Dispara la reconciliación con la cátedra: la primera vez al arrancar, y después a intervalos
@Component
@ConditionalOnProperty(name = "turnos.reservation.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final ReservationService reservationService;

    // Con espera fija, un ciclo no empieza hasta que termina el anterior: nunca hay dos reconciliaciones a la vez.
    // Puede tardar si la cátedra está lenta; las tareas tienen dos hilos para que eso no demore al barrido de vencidos
    @Scheduled(fixedDelayString = "${turnos.reservation.reconciliation.interval}")
    public void reconcile() {
        reservationService.reconcilePendingProcesses();
    }
}
