package turnos.catedra.infrastructure.client;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import turnos.catedra.domain.model.Appointment;
import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.ports.out.CatedraAppointmentClient;
import turnos.catedra.infrastructure.client.dto.AppointmentResponse;
import turnos.catedra.infrastructure.client.dto.CancelAppointmentRequest;
import turnos.catedra.infrastructure.client.dto.CancelAppointmentResponse;
import turnos.catedra.infrastructure.client.dto.CatedraErrorResponse;
import turnos.catedra.infrastructure.client.mapper.AppointmentMapper;

import java.net.http.HttpClient;
import java.util.List;

// Paquete infrastructure/client: excepción a la skill autorizada por el usuario.
// Hace un solo intento y traduce la respuesta. Cancelar se puede repetir siempre: la cátedra responde lo mismo si ya
// estaba cancelada, así que toda falla de red cuenta como no disponible
@Component
public class CatedraAppointmentClientAdapter implements CatedraAppointmentClient {

    private static final String CANCEL_PATH = "/api/appointments/{reservationProcessId}/cancel";
    private static final String LIST_PATH = "/api/appointments?externalPatientId={externalPatientId}&page={page}&size={size}";
    private static final ParameterizedTypeReference<List<AppointmentResponse>> APPOINTMENTS = new ParameterizedTypeReference<>() {
    };
    private static final String MISSING_INTEGRATION_CODE = "STUDENT_INTEGRATION_NOT_FOUND";

    private final CatedraProperties catedraProperties;
    private final AppointmentMapper appointmentMapper;
    private final RestClient restClient;

    public CatedraAppointmentClientAdapter(CatedraProperties catedraProperties, AppointmentMapper appointmentMapper) {
        this.catedraProperties = catedraProperties;
        this.appointmentMapper = appointmentMapper;
        CatedraProperties.Operations operations = catedraProperties.operations();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(operations.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(operations.readTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    // Sin motivo, el pedido va sin cuerpo: es opcional
    @Override
    public CatedraResponse<AppointmentCancellationResult> cancelAppointment(String reservationProcessId, String reason,
                                                                            String technicalToken) {
        RestClient.RequestBodySpec request = restClient.post()
                .uri(catedraProperties.baseUrl() + CANCEL_PATH, reservationProcessId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + technicalToken)
                .accept(MediaType.APPLICATION_JSON);
        if (reason != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(CancelAppointmentRequest.builder().reason(reason).build());
        }
        try {
            return request.exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                return switch (status) {
                    case 200 -> cancelled(response.bodyTo(CancelAppointmentResponse.class));
                    // El 401 llega sin cuerpo ni Content-Type: no se lee
                    case 401 -> new CatedraResponse.TokenRejected<AppointmentCancellationResult>();
                    case 403, 404, 409 -> rejected(status, response.bodyTo(CatedraErrorResponse.class));
                    case 500, 503 -> new CatedraResponse.Unavailable<AppointmentCancellationResult>("Catedra answered HTTP " + status);
                    default -> new CatedraResponse.Failed<AppointmentCancellationResult>("Catedra answered HTTP " + status);
                };
            });
        } catch (ResourceAccessException e) {
            return new CatedraResponse.Unavailable<>("Catedra is not reachable, did not answer in time or the connection was lost");
        } catch (RestClientException e) {
            return new CatedraResponse.Failed<>("Catedra answered an unreadable response");
        }
    }

    // Sin fechas: la cátedra responde 500 a todo listado con from y to. Una consulta no cambia nada, así que todo lo que
    // no llegó o pudo haber llegado es simplemente no disponible
    @Override
    public CatedraResponse<List<Appointment>> listAppointments(String externalPatientId, int page, int size,
                                                               String technicalToken) {
        try {
            return restClient.get()
                    .uri(catedraProperties.baseUrl() + LIST_PATH, externalPatientId, page, size)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + technicalToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((sent, response) -> {
                        int status = response.getStatusCode().value();
                        return switch (status) {
                            case 200 -> listed(response.bodyTo(APPOINTMENTS));
                            // El 401 llega sin cuerpo ni Content-Type: no se lee
                            case 401 -> new CatedraResponse.TokenRejected<List<Appointment>>();
                            case 403 -> forbidden(response.bodyTo(CatedraErrorResponse.class));
                            case 500, 503 -> new CatedraResponse.Unavailable<List<Appointment>>("Catedra answered HTTP " + status);
                            default -> new CatedraResponse.Failed<List<Appointment>>("Catedra answered HTTP " + status);
                        };
                    });
        } catch (ResourceAccessException e) {
            return new CatedraResponse.Unavailable<>("Catedra is not reachable, did not answer in time or the connection was lost");
        } catch (RestClientException e) {
            return new CatedraResponse.Failed<>("Catedra answered an unreadable response");
        }
    }

    private CatedraResponse<List<Appointment>> listed(List<AppointmentResponse> responses) {
        if (responses == null) {
            return new CatedraResponse.Failed<>("Catedra answered without a list of appointments");
        }
        return new CatedraResponse.Answered<>(appointmentMapper.toDomain(responses));
    }

    private static CatedraResponse<List<Appointment>> forbidden(CatedraErrorResponse error) {
        if (error != null && MISSING_INTEGRATION_CODE.equals(error.getCode())) {
            return new CatedraResponse.ConfigurationError<>("Catedra answered " + MISSING_INTEGRATION_CODE);
        }
        return new CatedraResponse.Failed<>("Catedra answered HTTP 403");
    }

    // Un 200 sin el estado esperado o la fecha no se puede usar: queda como respuesta no prevista
    private static CatedraResponse<AppointmentCancellationResult> cancelled(CancelAppointmentResponse response) {
        if (response == null || response.getCancelledAt() == null || !"CANCELLED".equals(response.getStatus())) {
            return new CatedraResponse.Failed<>("Catedra answered an incomplete cancellation");
        }
        return new CatedraResponse.Answered<>(new AppointmentCancellationResult.Cancelled(response.getCancelledAt()));
    }

    private static CatedraResponse<AppointmentCancellationResult> rejected(int status, CatedraErrorResponse error) {
        String code = error == null ? null : error.getCode();
        return switch (status + " " + code) {
            case "404 RESERVATION_NOT_FOUND" -> new CatedraResponse.Answered<>(new AppointmentCancellationResult.NotFound());
            case "403 RESERVATION_OWNER_MISMATCH" -> new CatedraResponse.Answered<>(new AppointmentCancellationResult.OwnerMismatch());
            case "409 RESERVATION_NOT_CANCELLABLE" -> new CatedraResponse.Answered<>(new AppointmentCancellationResult.NotCancellable());
            case "403 " + MISSING_INTEGRATION_CODE -> new CatedraResponse.ConfigurationError<>("Catedra answered " + MISSING_INTEGRATION_CODE);
            default -> new CatedraResponse.Failed<>("Catedra answered HTTP " + status + (code == null ? "" : " " + code));
        };
    }
}
