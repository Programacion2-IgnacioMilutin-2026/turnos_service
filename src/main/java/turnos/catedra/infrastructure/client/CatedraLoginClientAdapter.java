package turnos.catedra.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import turnos.catedra.domain.model.TechnicalLoginResult;
import turnos.catedra.domain.ports.out.TechnicalLoginClient;
import turnos.catedra.infrastructure.client.dto.TechnicalLoginRequest;
import turnos.catedra.infrastructure.client.dto.TechnicalLoginResponse;
import turnos.catedra.infrastructure.client.mapper.TechnicalIntegrationMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Paquete infrastructure/client: excepción a la skill autorizada por el usuario.
// Traduce las respuestas HTTP de la cátedra a resultados del dominio; nunca registra el JWT ni la contraseña
@Slf4j
@Component
public class CatedraLoginClientAdapter implements TechnicalLoginClient {

    private static final String LOGIN_PATH = "/api/authenticate";

    private final CatedraProperties catedraProperties;
    private final TechnicalIntegrationMapper technicalIntegrationMapper;
    private final List<String> missingProperties;
    private final boolean validBaseUrl;
    private final RestClient restClient;

    public CatedraLoginClientAdapter(CatedraProperties catedraProperties, TechnicalIntegrationMapper technicalIntegrationMapper) {
        this.catedraProperties = catedraProperties;
        this.technicalIntegrationMapper = technicalIntegrationMapper;
        this.missingProperties = missingProperties(catedraProperties);
        if (!missingProperties.isEmpty()) {
            log.warn("Technical login disabled: missing configuration properties {}", missingProperties);
        }
        this.validBaseUrl = isBlank(catedraProperties.baseUrl()) || isHttpUrl(catedraProperties.baseUrl());
        if (!validBaseUrl) {
            log.warn("Technical login disabled: turnos.catedra.base-url is not a valid http or https URL{}",
                    startsWithQuote(catedraProperties.baseUrl()) ? " (remove the quotes from the .env value)" : "");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(catedraProperties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(catedraProperties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public TechnicalLoginResult login() {
        if (!missingProperties.isEmpty()) {
            return new TechnicalLoginResult.ConfigurationError("Missing configuration properties " + missingProperties);
        }
        if (!validBaseUrl) {
            return new TechnicalLoginResult.ConfigurationError("turnos.catedra.base-url is not a valid http or https URL");
        }
        try {
            TechnicalLoginResponse response = restClient.post()
                    .uri(catedraProperties.baseUrl() + LOGIN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(TechnicalLoginRequest.builder()
                            .username(catedraProperties.username())
                            .password(catedraProperties.password())
                            .rememberMe(true)
                            .build())
                    .retrieve()
                    .body(TechnicalLoginResponse.class);
            if (response == null || response.getIdToken() == null || response.getIntegration() == null) {
                return new TechnicalLoginResult.Unavailable("Central API answered an incomplete login response");
            }
            return new TechnicalLoginResult.Success(response.getIdToken(), technicalIntegrationMapper.toDomain(response.getIntegration()));
        } catch (HttpClientErrorException e) {
            return new TechnicalLoginResult.ConfigurationError(
                    "Central API rejected the technical login with HTTP " + e.getStatusCode().value() + codeOf(e));
        } catch (HttpServerErrorException e) {
            return new TechnicalLoginResult.Unavailable("Central API answered HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            return new TechnicalLoginResult.Unavailable("Central API is not reachable or did not answer in time");
        } catch (RestClientException e) {
            return new TechnicalLoginResult.Unavailable("Central API answered an unreadable login response");
        } catch (RuntimeException e) {
            // Un error no previsto nunca debe cortar el login: cuenta como cátedra no disponible
            return new TechnicalLoginResult.Unavailable("Unexpected error during the technical login: " + e.getClass().getSimpleName());
        }
    }

    private String codeOf(HttpClientErrorException e) {
        try {
            Map<?, ?> problem = e.getResponseBodyAs(Map.class);
            return problem != null && problem.get("code") != null ? " (" + problem.get("code") + ")" : "";
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    private static List<String> missingProperties(CatedraProperties properties) {
        List<String> missing = new ArrayList<>();
        if (isBlank(properties.baseUrl())) {
            missing.add("turnos.catedra.base-url");
        }
        if (isBlank(properties.username())) {
            missing.add("turnos.catedra.username");
        }
        if (isBlank(properties.password())) {
            missing.add("turnos.catedra.password");
        }
        return List.copyOf(missing);
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            boolean httpScheme = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            return httpScheme && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean startsWithQuote(String value) {
        return value.startsWith("\"") || value.startsWith("'");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
