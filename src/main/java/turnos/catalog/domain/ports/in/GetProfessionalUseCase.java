package turnos.catalog.domain.ports.in;

import turnos.catalog.domain.model.ProfessionalResult;

public interface GetProfessionalUseCase {
    ProfessionalResult getProfessional(Long professionalId, String userToken);
}
