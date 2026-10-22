package turnos.integration.domain.ports.out;

import turnos.integration.domain.model.TechnicalSession;

public interface TechnicalSessionRepository {

    TechnicalSession get();
    void save(TechnicalSession technicalSession);

}
