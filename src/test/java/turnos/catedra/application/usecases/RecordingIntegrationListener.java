package turnos.catedra.application.usecases;

import turnos.catedra.domain.model.TechnicalIntegration;
import turnos.catedra.domain.ports.out.TechnicalIntegrationListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// Guarda cada aviso de integración habilitada, para comprobar cuándo y con qué datos se avisa.
// Con failing(), además falla después de guardarlo
public class RecordingIntegrationListener implements TechnicalIntegrationListener {

    private final List<TechnicalIntegration> notified = new CopyOnWriteArrayList<>();
    private boolean failing;

    public RecordingIntegrationListener failing() {
        failing = true;
        return this;
    }

    public List<TechnicalIntegration> notified() {
        return notified;
    }

    @Override
    public void integrationEnabled(TechnicalIntegration integration) {
        notified.add(integration);
        if (failing) {
            throw new IllegalStateException("listener failure");
        }
    }
}
