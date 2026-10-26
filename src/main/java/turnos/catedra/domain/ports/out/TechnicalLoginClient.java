package turnos.catedra.domain.ports.out;

import turnos.catedra.domain.model.TechnicalLoginResult;

public interface TechnicalLoginClient {

    TechnicalLoginResult login();

}
