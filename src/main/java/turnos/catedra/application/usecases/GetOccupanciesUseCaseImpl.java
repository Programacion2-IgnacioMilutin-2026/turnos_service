package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.CatedraOperationPolicy;
import turnos.catedra.domain.model.IntegrationState;
import turnos.catedra.domain.model.OccupanciesResult;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.in.GetOccupanciesUseCase;
import turnos.catedra.domain.ports.in.RenewTechnicalSessionUseCase;
import turnos.catedra.domain.ports.out.CatedraOccupancyClient;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetOccupanciesUseCaseImpl implements GetOccupanciesUseCase {

    private final CatedraOccupancyClient catedraOccupancyClient;
    private final TechnicalSessionRepository technicalSessionRepository;
    private final RenewTechnicalSessionUseCase renewTechnicalSessionUseCase;
    private final CatedraOperationPolicy catedraOperationPolicy;

    // Con la sesión habilitada usa su JWT; degradada, pide un login antes; en error de configuración no consulta.
    // Si la cátedra rechaza el JWT, renueva la sesión y repite una sola vez
    @Override
    public OccupanciesResult getOccupancies(Long professionalId, LocalDate date) {
        TechnicalSession session = technicalSessionRepository.get();
        if (session.state() == IntegrationState.CONFIGURATION_ERROR) {
            return new OccupanciesResult.ConfigurationError("Technical integration has a configuration error");
        }
        if (!session.isEnabled()) {
            Optional<TechnicalSession> renewed = renewTechnicalSessionUseCase.renewTechnicalSession(session.token());
            if (renewed.isEmpty()) {
                return noSession();
            }
            session = renewed.get();
        }
        OccupanciesResult result = fetchWithRetries(professionalId, date, session.token());
        if (result instanceof OccupanciesResult.TokenRejected) {
            Optional<TechnicalSession> renewed = renewTechnicalSessionUseCase.renewTechnicalSession(session.token());
            if (renewed.isEmpty()) {
                return noSession();
            }
            result = fetchWithRetries(professionalId, date, renewed.get().token());
            if (result instanceof OccupanciesResult.TokenRejected) {
                log.warn("Catedra rejected the renewed technical token for the occupancies of professional {}", professionalId);
                return new OccupanciesResult.Unavailable("Catedra rejected the renewed technical token");
            }
        }
        if (result instanceof OccupanciesResult.ConfigurationError error) {
            log.error("Catedra occupancies configuration error: {}", error.reason());
        }
        return result;
    }

    // Solo se reintenta cuando la cátedra no está disponible; cualquier otra respuesta se devuelve al primer intento
    private OccupanciesResult fetchWithRetries(Long professionalId, LocalDate date, String token) {
        int maxAttempts = catedraOperationPolicy.maxAttempts();
        OccupanciesResult result = new OccupanciesResult.Unavailable("Catedra request was not attempted");
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!waitBefore(catedraOperationPolicy.delayBeforeAttempt(attempt))) {
                break;
            }
            result = fetchSafely(professionalId, date, token);
            if (!(result instanceof OccupanciesResult.Unavailable unavailable)) {
                return result;
            }
            log.warn("Catedra occupancies request for professional {} attempt {}/{} failed: {}",
                    professionalId, attempt, maxAttempts, unavailable.reason());
        }
        return result;
    }

    // Un error no previsto es un fallo propio, no una cátedra caída: no se reintenta
    private OccupanciesResult fetchSafely(Long professionalId, LocalDate date, String token) {
        try {
            return catedraOccupancyClient.fetchOccupancies(professionalId, date, token);
        } catch (RuntimeException e) {
            log.error("Unexpected error requesting the occupancies of professional {}: {}", professionalId, e.getClass().getSimpleName());
            return new OccupanciesResult.Failed("Unexpected error: " + e.getClass().getSimpleName());
        }
    }

    private static OccupanciesResult noSession() {
        return new OccupanciesResult.Unavailable("No technical session available");
    }

    private static boolean waitBefore(Duration delay) {
        if (delay.isZero()) {
            return true;
        }
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
