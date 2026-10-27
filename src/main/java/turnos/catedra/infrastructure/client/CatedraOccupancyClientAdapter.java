package turnos.catedra.infrastructure.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.Occupancy;
import turnos.catedra.domain.ports.out.CatedraOccupancyClient;
import turnos.catedra.infrastructure.client.dto.CatedraErrorResponse;
import turnos.catedra.infrastructure.client.dto.OccupanciesResponse;
import turnos.catedra.infrastructure.client.mapper.OccupancyMapper;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;

// Paquete infrastructure/client: excepción a la skill autorizada por el usuario.
// Hace un solo intento y traduce la respuesta; los reintentos y la renovación de la sesión los decide el caso de uso.
// Sin una URL válida nunca se llega acá: el login técnico falla antes y la sesión queda en error de configuración
@Component
public class CatedraOccupancyClientAdapter implements CatedraOccupancyClient {

    private static final String OCCUPANCIES_PATH = "/api/appointment-occupancies?professionalId={professionalId}&from={from}&to={to}";
    private static final String MISSING_INTEGRATION_CODE = "STUDENT_INTEGRATION_NOT_FOUND";

    private final CatedraProperties catedraProperties;
    private final OccupancyMapper occupancyMapper;
    private final RestClient restClient;

    public CatedraOccupancyClientAdapter(CatedraProperties catedraProperties, OccupancyMapper occupancyMapper) {
        this.catedraProperties = catedraProperties;
        this.occupancyMapper = occupancyMapper;
        CatedraProperties.Operations operations = catedraProperties.operations();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(operations.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(operations.readTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    // Se consulta un solo día: desde y hasta son la misma fecha. Una consulta no crea nada, así que todo lo que no
    // llegó o pudo haber llegado es simplemente no disponible
    @Override
    public CatedraResponse<List<Occupancy>> fetchOccupancies(Long professionalId, LocalDate date, String technicalToken) {
        try {
            return restClient.get()
                    .uri(catedraProperties.baseUrl() + OCCUPANCIES_PATH, professionalId, date, date)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + technicalToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        return switch (status) {
                            case 200 -> found(response.bodyTo(OccupanciesResponse.class));
                            // El 401 llega sin cuerpo ni Content-Type: no se lee
                            case 401 -> new CatedraResponse.TokenRejected<>();
                            case 403 -> forbidden(response.bodyTo(CatedraErrorResponse.class));
                            case 500, 503 -> new CatedraResponse.Unavailable<>("Catedra answered HTTP " + status);
                            default -> new CatedraResponse.Failed<>("Catedra answered HTTP " + status);
                        };
                    });
        } catch (ResourceAccessException e) {
            return new CatedraResponse.Unavailable<>("Catedra is not reachable or did not answer in time");
        } catch (RestClientException e) {
            return new CatedraResponse.Failed<>("Catedra answered an unreadable response");
        }
    }

    private CatedraResponse<List<Occupancy>> found(OccupanciesResponse response) {
        if (response == null || response.getOccupancies() == null) {
            return new CatedraResponse.Failed<>("Catedra answered without occupancies");
        }
        try {
            return new CatedraResponse.Answered<>(occupancyMapper.toDomain(response.getOccupancies()));
        } catch (IllegalArgumentException e) {
            return new CatedraResponse.Failed<>("Catedra answered an occupancy with an unknown status");
        }
    }

    private static CatedraResponse<List<Occupancy>> forbidden(CatedraErrorResponse error) {
        if (error != null && MISSING_INTEGRATION_CODE.equals(error.getCode())) {
            return new CatedraResponse.ConfigurationError<>("Catedra answered " + MISSING_INTEGRATION_CODE);
        }
        return new CatedraResponse.Failed<>("Catedra answered HTTP 403");
    }
}
