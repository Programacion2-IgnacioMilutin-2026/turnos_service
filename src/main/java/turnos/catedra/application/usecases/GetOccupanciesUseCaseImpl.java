package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.application.support.CatedraOperationRunner;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.OccupanciesResult;
import turnos.catedra.domain.model.Occupancy;
import turnos.catedra.domain.ports.in.GetOccupanciesUseCase;
import turnos.catedra.domain.ports.out.CatedraOccupancyClient;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class GetOccupanciesUseCaseImpl implements GetOccupanciesUseCase {

    private final CatedraOccupancyClient catedraOccupancyClient;
    private final CatedraOperationRunner catedraOperationRunner;

    // Una consulta no crea nada: si no se sabe si llegó, la cátedra se trata como no disponible
    @Override
    public OccupanciesResult getOccupancies(Long professionalId, LocalDate date) {
        CatedraResponse<List<Occupancy>> response = catedraOperationRunner.run(
                "occupancies request for professional " + professionalId,
                token -> catedraOccupancyClient.fetchOccupancies(professionalId, date, token));
        return switch (response) {
            case CatedraResponse.Answered<List<Occupancy>> answered -> new OccupanciesResult.Found(answered.content());
            case CatedraResponse.ConfigurationError<List<Occupancy>> error -> new OccupanciesResult.ConfigurationError(error.reason());
            case CatedraResponse.Unavailable<List<Occupancy>> unavailable -> new OccupanciesResult.Unavailable(unavailable.reason());
            case CatedraResponse.Uncertain<List<Occupancy>> uncertain -> new OccupanciesResult.Unavailable(uncertain.reason());
            // El componente común renueva la sesión ante un rechazo; si igual llegara uno, la cátedra no está disponible
            case CatedraResponse.TokenRejected<List<Occupancy>> _ -> new OccupanciesResult.Unavailable("Technical token rejected");
            case CatedraResponse.Failed<List<Occupancy>> failed -> new OccupanciesResult.Failed(failed.reason());
        };
    }
}
