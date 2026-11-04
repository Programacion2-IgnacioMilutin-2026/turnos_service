package turnos.reservation.infrastructure.scheduling;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import turnos.reservation.application.service.ReservationService;

// Paquete infrastructure/scheduling: excepción a la skill autorizada por el usuario.
// Dispara el barrido de procesos vencidos: la primera vez al arrancar, y después a intervalos
@Component
@ConditionalOnProperty(name = "turnos.reservation.sweep.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OverdueProcessSweepScheduler {

    private final ReservationService reservationService;

    // Con espera fija, la siguiente corrida no empieza hasta que termina la anterior: es lo que impide que dos barridos
    // se solapen. Con frecuencia fija (fixedRate) dejaría de estar garantizado
    @Scheduled(fixedDelayString = "${turnos.reservation.sweep.interval}")
    public void sweep() {
        reservationService.expireOverdueProcesses();
    }
}
