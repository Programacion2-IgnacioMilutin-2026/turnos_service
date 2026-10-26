package turnos.catedra.domain.ports.out;

import turnos.catedra.domain.model.TechnicalSession;

public interface TechnicalSessionRepository {

    TechnicalSession get();
    void save(TechnicalSession technicalSession);

}
