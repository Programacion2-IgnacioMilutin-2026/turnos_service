package turnos.catedra.infrastructure.persistence.adapter;

import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.util.concurrent.atomic.AtomicReference;

// Guarda la sesión técnica solo en memoria, sin base de datos: el JWT técnico no se persiste.
// Cada save reemplaza la sesión entera de una vez, así ningún request ve una sesión a medio actualizar
@Component
public class InMemoryTechnicalSessionRepositoryAdapter implements TechnicalSessionRepository {

    private final AtomicReference<TechnicalSession> current = new AtomicReference<>(TechnicalSession.connecting());

    @Override
    public TechnicalSession get() {
        return current.get();
    }

    @Override
    public void save(TechnicalSession technicalSession) {
        current.set(technicalSession);
    }
}
