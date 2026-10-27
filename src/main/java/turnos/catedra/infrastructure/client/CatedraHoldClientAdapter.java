package turnos.catedra.infrastructure.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.HoldResult;
import turnos.catedra.domain.ports.out.CatedraHoldClient;
import turnos.catedra.infrastructure.client.dto.CatedraErrorResponse;
import turnos.catedra.infrastructure.client.dto.CreateHoldRequest;
import turnos.catedra.infrastructure.client.dto.HoldResponse;
import turnos.catedra.infrastructure.client.mapper.HoldMapper;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

// Paquete infrastructure/client: excepción a la skill autorizada por el usuario.
// Hace un solo intento y traduce la respuesta. Como pedir un hold lo crea, distingue lo que seguro no llegó a la
// cátedra (se puede repetir) de lo que pudo haber llegado sin que volviera la respuesta (no se debe repetir)
@Component
public class CatedraHoldClientAdapter implements CatedraHoldClient {

    private static final String HOLDS_PATH = "/api/appointment-holds";
    private static final String MISSING_INTEGRATION_CODE = "STUDENT_INTEGRATION_NOT_FOUND";
    private static final DateTimeFormatter HOUR_AND_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final CatedraProperties catedraProperties;
    private final HoldMapper holdMapper;
    private final RestClient restClient;

    public CatedraHoldClientAdapter(CatedraProperties catedraProperties, HoldMapper holdMapper) {
        this.catedraProperties = catedraProperties;
        this.holdMapper = holdMapper;
        CatedraProperties.Operations operations = catedraProperties.operations();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(operations.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(operations.readTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public CatedraResponse<HoldResult> createHold(Long professionalId, LocalDate date, LocalTime startTime,
                                                  String externalPatientId, String technicalToken) {
        CreateHoldRequest body = CreateHoldRequest.builder()
                .professionalId(professionalId)
                .date(date)
                .startTime(startTime.format(HOUR_AND_MINUTE))
                .externalPatientId(externalPatientId)
                .build();
        try {
            return restClient.post()
                    .uri(catedraProperties.baseUrl() + HOLDS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + technicalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        return switch (status) {
                            case 201 -> created(response.bodyTo(HoldResponse.class));
                            // El 401 llega sin cuerpo ni Content-Type: no se lee
                            case 401 -> new CatedraResponse.TokenRejected<HoldResult>();
                            case 400, 403, 404, 409 -> rejected(status, response.bodyTo(CatedraErrorResponse.class));
                            case 503 -> new CatedraResponse.Unavailable<HoldResult>("Catedra answered HTTP 503");
                            // Un error interno pudo ocurrir después de crear el hold
                            case 500 -> new CatedraResponse.Uncertain<HoldResult>("Catedra answered HTTP 500");
                            default -> new CatedraResponse.Failed<HoldResult>("Catedra answered HTTP " + status);
                        };
                    });
        } catch (ResourceAccessException e) {
            return wasNotSent(e)
                    ? new CatedraResponse.Unavailable<>("Catedra is not reachable")
                    : new CatedraResponse.Uncertain<>("Catedra did not answer in time or the connection was cut");
        } catch (RestClientException e) {
            return new CatedraResponse.Failed<>("Catedra answered an unreadable response");
        }
    }

    // No poder conectar (rechazo, timeout de conexión o un host desconocido) asegura que el pedido no salió; cualquier
    // otra falla de red, como un timeout de respuesta o un corte, pudo ocurrir con el pedido ya recibido por la cátedra
    static boolean wasNotSent(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                    || cause instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    // Un 201 sin los datos del hold no se puede usar, aunque el hold exista: queda como respuesta no prevista
    private CatedraResponse<HoldResult> created(HoldResponse response) {
        if (response == null || response.getHoldId() == null || response.getReservationProcessId() == null
                || response.getExpiresAt() == null || !"HELD".equals(response.getStatus())) {
            return new CatedraResponse.Failed<>("Catedra answered an incomplete hold");
        }
        return new CatedraResponse.Answered<>(new HoldResult.Created(holdMapper.toDomain(response)));
    }

    private static CatedraResponse<HoldResult> rejected(int status, CatedraErrorResponse error) {
        String code = error == null ? null : error.getCode();
        HoldResult result = switch (status + " " + code) {
            case "404 PROFESSIONAL_NOT_FOUND" -> new HoldResult.ProfessionalNotFound();
            case "400 PROFESSIONAL_DISABLED" -> new HoldResult.ProfessionalDisabled();
            case "400 INVALID_SLOT" -> new HoldResult.InvalidSlot();
            case "409 SLOT_ALREADY_HELD", "409 SLOT_ALREADY_RESERVED" -> new HoldResult.SlotNotAvailable();
            default -> null;
        };
        if (result != null) {
            return new CatedraResponse.Answered<>(result);
        }
        if (status == 403 && MISSING_INTEGRATION_CODE.equals(code)) {
            return new CatedraResponse.ConfigurationError<>("Catedra answered " + MISSING_INTEGRATION_CODE);
        }
        return new CatedraResponse.Failed<>("Catedra answered HTTP " + status + (code == null ? "" : " " + code));
    }
}
