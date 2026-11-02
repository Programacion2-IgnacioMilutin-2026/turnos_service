package turnos.catedra.domain.ports.out;

import turnos.catedra.domain.model.TechnicalIntegration;

// Recibe el aviso de que un login dejó la integración habilitada, con los datos que informó la cátedra
public interface TechnicalIntegrationListener {

    void integrationEnabled(TechnicalIntegration integration);

}
