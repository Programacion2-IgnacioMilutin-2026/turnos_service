package turnos.catalog.domain.ports.out;

import turnos.catalog.domain.model.ProfessionalResult;

public interface CatalogClient {

    ProfessionalResult fetchProfessional(Long professionalId, String userToken);

}
