package turnos.catedra.infrastructure.startup;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import turnos.catedra.application.service.TechnicalSessionService;

// Paquete infrastructure/startup: excepción a la skill autorizada por el usuario.
// Dispara el login técnico cuando la aplicación terminó de arrancar
@Component
@RequiredArgsConstructor
public class TechnicalSessionStartup {

    private final TechnicalSessionService technicalSessionService;

    // En un hilo virtual aparte: el servicio atiende T01 y T02 sin esperar a la cátedra
    @EventListener(ApplicationReadyEvent.class)
    public void startTechnicalSession() {
        Thread.ofVirtual().name("technical-login").start(technicalSessionService::startTechnicalSession);
    }
}
