package turnos.integration.domain.ports.out;

import turnos.integration.domain.model.TechnicalLoginResult;

public interface TechnicalLoginClient {

    TechnicalLoginResult login();

}
