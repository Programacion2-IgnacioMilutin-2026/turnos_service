package turnos.catalog.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catalog.domain.model.ProfessionalResult;
import turnos.catalog.domain.ports.in.GetProfessionalUseCase;
import turnos.catalog.domain.ports.out.CatalogClient;

@Component
@RequiredArgsConstructor
public class GetProfessionalUseCaseImpl implements GetProfessionalUseCase {

    private final CatalogClient catalogClient;

    @Override
    public ProfessionalResult getProfessional(Long professionalId, String userToken) {
        return catalogClient.fetchProfessional(professionalId, userToken);
    }

}
